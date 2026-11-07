package com.coke.nestspace.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads a browser resource into the system Downloads collection. */
object BrowserDownloader {

    private const val MAX_REDIRECTS = 5

    data class DownloadResult(
        val name: String,
        val path: String,
        val uri: String,
        val size: Long
    )

    suspend fun download(
        context: Context,
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): DownloadResult = withContext(Dispatchers.IO) {
        var currentUrl = url
        var redirects = 0
        while (true) {
            val parsed = Uri.parse(currentUrl)
            require(parsed.scheme == "http" || parsed.scheme == "https") {
                "Unsupported URL scheme: ${parsed.scheme}"
            }
            val proxy = BrowserProxySettings.connectionProxy(
                BrowserProxySettings.load(context),
                currentUrl
            )
            val remote = URL(currentUrl)
            val connection = (if (proxy != null) {
                remote.openConnection(proxy)
            } else {
                remote.openConnection()
            } as HttpURLConnection).apply {
                // Follow redirects manually so a cookie for one site is never
                // forwarded to a different host (HttpURLConnection otherwise
                // keeps custom request headers across redirects).
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", BrowserSession.MOBILE_USER_AGENT)
                CookieManager.getInstance().getCookie(currentUrl)?.let {
                    setRequestProperty("Cookie", it)
                }
            }
            try {
                connection.connect()
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?.takeIf { it.isNotBlank() }
                        ?: throw IOException("HTTP $code without Location")
                    if (redirects++ >= MAX_REDIRECTS) throw IOException("Too many redirects")
                    currentUrl = URL(remote, location).toString()
                    continue
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                val contentDisposition = connection.getHeaderField("Content-Disposition")
                val contentType = connection.contentType.orEmpty()
                val name = uniqueName(
                    context,
                    fileName(currentUrl, contentDisposition, contentType)
                )
                val total = connection.contentLengthLong
                onProgress(0L, total)
                return@withContext if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writeToMediaStore(context, connection, name, contentType, total, onProgress)
                } else {
                    writeToLegacyDownloads(context, connection, name, total, onProgress)
                }
            } finally {
                connection.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("Download failed")
    }

    private fun writeToMediaStore(
        context: Context,
        connection: HttpURLConnection,
        name: String,
        contentType: String,
        total: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): DownloadResult {
        val resolver = context.contentResolver
        val mime = when {
            name.endsWith(".apk", true) -> "application/vnd.android.package-archive"
            contentType.isNotBlank() -> contentType.substringBefore(';').trim()
            else -> "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Cannot create a file in the system Downloads directory")
        try {
            connection.inputStream.use { input ->
                resolver.openOutputStream(uri)?.use { output ->
                    copyWithProgress(input, output, total, onProgress)
                } ?: throw IOException("Cannot open Downloads file")
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val actualName = queryName(resolver, uri) ?: name
            val publicPath = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                actualName
            ).absolutePath
            val size = querySize(resolver, uri)
            if (size <= 0L) throw IOException("Downloaded file is empty")
            return DownloadResult(actualName, publicPath, uri.toString(), size)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun writeToLegacyDownloads(
        context: Context,
        connection: HttpURLConnection,
        name: String,
        total: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): DownloadResult {
        // Public Downloads needs WRITE_EXTERNAL_STORAGE before Android 10.
        // The app never prompts for it, so fall back to the app-specific
        // external directory when the public directory is not writable.
        val dirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            File(context.filesDir, "downloads")
        )
        var lastError: Throwable? = null
        for (dir in dirs) {
            dir.mkdirs()
            var target = File(dir, name)
            var suffix = 1
            while (target.exists()) {
                val base = name.substringBeforeLast('.', name)
                val extension = name.substringAfterLast('.', "")
                    .let { if (it.isEmpty()) "" else ".$it" }
                target = File(dir, "$base-${suffix++}$extension")
            }
            val output = try {
                target.outputStream()
            } catch (t: Throwable) {
                lastError = t
                continue
            }
            output.use { to ->
                connection.inputStream.use { from ->
                    copyWithProgress(from, to, total, onProgress)
                }
            }
            if (!target.isFile || target.length() == 0L) {
                throw IOException("Downloaded file is empty")
            }
            return DownloadResult(
                target.name,
                target.absolutePath,
                Uri.fromFile(target).toString(),
                target.length()
            )
        }
        throw IOException(lastError?.message ?: "Cannot write the download", lastError)
    }

    private fun copyWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val buffer = ByteArray(64 * 1024)
        var downloaded = 0L
        var lastReported = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            downloaded += count
            if (downloaded - lastReported >= 256 * 1024 ||
                (total > 0L && downloaded >= total)
            ) {
                lastReported = downloaded
                onProgress(downloaded, total)
            }
        }
        output.flush()
        onProgress(downloaded, total)
    }

    private fun uniqueName(context: Context, name: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return name
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        var candidate = name
        var suffix = 1
        while (exists(resolver, projection, candidate)) {
            val base = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            candidate = "$base-${suffix++}$extension"
        }
        return candidate
    }

    private fun queryName(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun querySize(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): Long = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    private fun exists(
        resolver: android.content.ContentResolver,
        projection: Array<String>,
        name: String
    ): Boolean = runCatching {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(name),
            null
        )?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    private fun fileName(url: String, disposition: String?, contentType: String): String {
        val fromHeader = disposition?.let { value ->
            Regex("""filename\*?=(?:UTF-8''|")?([^";]+)""", RegexOption.IGNORE_CASE)
                .find(value)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.trim('"')
        }
        val fromUrl = runCatching {
            Uri.parse(url).lastPathSegment?.substringBefore('?')
        }.getOrNull()
        var name = fromHeader?.takeIf { it.isNotBlank() }
            ?: fromUrl?.takeIf { it.isNotBlank() }
            ?: "download-${System.currentTimeMillis()}.apk"
        name = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(120)
        // "." and ".." are directory entries, not file names.
        if (name.isBlank() || name.all { it == '.' }) name = "download"
        if (!name.contains('.')) {
            name += if (contentType.contains("android.package-archive")) ".apk" else ".bin"
        }
        return name
    }
}

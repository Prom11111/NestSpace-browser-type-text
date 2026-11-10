package com.coke.nestspace.assistant

import com.coke.nestspace.i18n.AppLanguage
import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Downloads and caches the small Vosk language model used by the on-device
 * recognizer. Recognition itself runs entirely in libvosk; only the one-time
 * model download needs the network.
 *
 * Downloads are process-scoped and de-duplicated, so closing/reopening the
 * voice sheet does not restart a download that is already in flight.
 */
internal object LocalSpeechModelStore {

    data class Mirror(
        val label: String,
        val url: String,
        val probeUrl: String
    )

    data class Spec(
        val language: String,
        val displayName: String,
        val mirrors: List<Mirror>,
        val approximateMb: Int
    )

    data class DownloadMetrics(
        val bytesPerSecond: Long = 0L,
        val remainingSeconds: Long = -1L
    ) {
        fun speedText(): String {
            if (bytesPerSecond <= 0L) return ""
            val kb = bytesPerSecond / 1024.0
            return if (kb < 1024.0) {
                String.format(Locale.US, "%.0f KB/s", kb)
            } else {
                String.format(Locale.US, "%.1f MB/s", kb / 1024.0)
            }
        }

        fun remainingText(): String {
            if (remainingSeconds < 0L) return ""
            val total = remainingSeconds.coerceAtLeast(0L)
            val hours = total / 3600L
            val minutes = total % 3600L / 60L
            val seconds = total % 60L
            return if (hours > 0L) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }

    private fun buildMirrors(path: String): List<Mirror> = listOf(
        // Hugging Face mirror operated for mainland China users.
        Mirror(
            AppLanguage.tr("HF 国内镜像", "HF mirror (China)"),
            "https://hf-mirror.com/rhasspy/vosk-models/resolve/main/$path",
            "https://hf-mirror.com/"
        ),
        Mirror(
            "Hugging Face",
            "https://huggingface.co/rhasspy/vosk-models/resolve/main/$path",
            "https://huggingface.co/"
        ),
        Mirror(
            AppLanguage.tr("Vosk 官方", "Vosk official"),
            "https://alphacephei.com/vosk/models/${path.substringAfterLast('/')}",
            "https://alphacephei.com/vosk/"
        )
    )

    private val specs = mapOf(
        "zh" to Spec(
            language = "zh",
            displayName = "中文",
            mirrors = buildMirrors("zh/vosk-model-small-cn-0.22.zip"),
            approximateMb = 42
        ),
        "en" to Spec(
            language = "en",
            displayName = "English",
            mirrors = buildMirrors("en/vosk-model-small-en-us-0.15.zip"),
            approximateMb = 40
        )
    )

    private val downloads = ConcurrentHashMap<String, Deferred<File>>()
    private val manualSources = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _metrics =
        MutableStateFlow<Map<String, DownloadMetrics>>(emptyMap())
    private val _source = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _revision = MutableStateFlow(0L)

    /** -1 = failed, 0..99 = downloading, 100 = ready. */
    val progress: StateFlow<Map<String, Int>> = _progress

    /** Current transfer speed and estimated remaining time. */
    val metrics: StateFlow<Map<String, DownloadMetrics>> = _metrics

    /** Label of the mirror selected after response-time probing. */
    val source: StateFlow<Map<String, String>> = _source

    /** Bumped on deletion/installation so settings re-check the model dir. */
    val revision: StateFlow<Long> = _revision

    fun spec(language: String): Spec = specs[language] ?: specs.getValue("zh")

    fun mirrors(language: String): List<Mirror> = spec(language).mirrors

    fun switchDownloadSource(context: Context, language: String, label: String) {
        val app = context.applicationContext
        val spec = spec(language)
        val mirror = spec.mirrors.firstOrNull { it.label == label } ?: return
        manualSources[spec.language] = mirror.label
        downloads.remove(spec.language)?.cancel()
        _progress.update { it + (spec.language to 0) }
        _metrics.update {
            it + (spec.language to DownloadMetrics())
        }
        _source.update { it + (spec.language to mirror.label) }
        scope.launch {
            runCatching { ensure(app, spec.language) }
        }
    }

    fun modelDir(context: Context, language: String): File =
        File(context.applicationContext.filesDir, "vosk/model-${spec(language).language}")

    fun isReady(context: Context, language: String): Boolean =
        File(modelDir(context, language), "am/final.mdl").isFile

    fun delete(context: Context, language: String) {
        val spec = spec(language)
        modelDir(context, spec.language).deleteRecursively()
        _progress.update { it - spec.language }
        _metrics.update { it - spec.language }
        _source.update { it - spec.language }
        _revision.update { it + 1 }
    }

    suspend fun ensure(context: Context, language: String): File {
        val app = context.applicationContext
        val spec = spec(language)
        val target = modelDir(app, spec.language)
        if (isReady(app, spec.language)) {
            LocalSpeechEngine.preload(app, spec.language)
            return target
        }

        val deferred = downloads[spec.language] ?: run {
            val job = scope.async {
                try {
                    install(app, spec, target)
                    manualSources.remove(spec.language)
                    _progress.update { it + (spec.language to 100) }
                    _metrics.update { it - spec.language }
                    _revision.update { it + 1 }
                    // Load immediately after a successful download, so the
                    // next voice interaction doesn't wait for model init.
                    LocalSpeechEngine.preload(app, spec.language)
                    target
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    _progress.update { it + (spec.language to -1) }
                    _metrics.update { it - spec.language }
                    throw t
                }
            }
            downloads[spec.language] = job
            job.invokeOnCompletion { downloads.remove(spec.language, job) }
            job
        }
        return deferred.await()
    }

    private suspend fun install(context: Context, spec: Spec, target: File) {
        val voskRoot = File(context.filesDir, "vosk").apply { mkdirs() }
        val downloadId = UUID.randomUUID().toString().take(8)
        val zip = File(context.cacheDir, "vosk-${spec.language}-$downloadId.zip")
        val temp = File(voskRoot, "model-${spec.language}-$downloadId.tmp")
        try {
            _progress.update { it + (spec.language to 0) }
            _metrics.update {
                it + (spec.language to DownloadMetrics())
            }
            val ordered = chooseMirrors(
                spec,
                manualSources[spec.language]
            )
            var lastError: Throwable? = null
            for (mirror in ordered) {
                try {
                    Log.i(TAG, "downloading ${spec.language} from ${mirror.label}")
                    _source.update { it + (spec.language to mirror.label) }
                    download(mirror, zip) { percent, metrics ->
                        _progress.update { it + (spec.language to percent) }
                        _metrics.update {
                            it + (spec.language to metrics)
                        }
                    }
                    lastError = null
                    break
                } catch (t: Throwable) {
                    lastError = t
                    zip.delete()
                }
            }
            if (lastError != null) {
                throw lastError ?: IOException(AppLanguage.tr("语音模型下载失败", "Speech model download failed"))
            }
            currentCoroutineContext().ensureActive()
            extract(zip, temp)
            if (!File(temp, "am/final.mdl").isFile) {
                throw IOException(AppLanguage.tr("语音模型文件不完整", "The speech model file is incomplete"))
            }
            if (target.exists()) target.deleteRecursively()
            if (!temp.renameTo(target)) {
                throw IOException(AppLanguage.tr("无法保存语音模型", "Could not save the speech model"))
            }
        } finally {
            zip.delete()
            if (temp.exists()) temp.deleteRecursively()
        }
    }

    private suspend fun chooseMirrors(spec: Spec, preferredLabel: String?): List<Mirror> {
        val preferred = preferredLabel?.let { label ->
            spec.mirrors.firstOrNull { it.label == label }
        }
        if (preferred != null) {
            return listOf(preferred) + spec.mirrors.filterNot {
                it.label == preferred.label
            }
        }
        val results = coroutineScope {
            spec.mirrors.map { mirror ->
                async(Dispatchers.IO) {
                    mirror to probe(mirror)
                }
            }.awaitAll()
        }
        Log.i(
            TAG,
            "mirror probe: " + results.joinToString {
                "${it.first.label}=${String.format(Locale.US, "%.1f", it.second)}KB/s"
            }
        )
        return results
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun probe(mirror: Mirror): Double {
        var best = -1.0
        repeat(MIRROR_TESTS) {
            best = maxOf(best, probeOnce(mirror))
        }
        return best
    }

    private fun probeOnce(mirror: Mirror): Double {
        var connection: HttpURLConnection? = null
        return try {
            val startedAt = System.nanoTime()
            connection = (URL(mirror.probeUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "NestSpace/1.0")
            }
            connection.connect()
            if (connection.responseCode !in 200..299) return -1.0
            val buffer = ByteArray(8 * 1024)
            var total = 0
            connection.inputStream.use { input ->
                while (total < PROBE_BYTES) {
                    val want = minOf(buffer.size, PROBE_BYTES - total)
                    val read = input.read(buffer, 0, want)
                    if (read < 0) break
                    total += read
                }
            }
            if (total <= 0) {
                -1.0
            } else {
                val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000.0)
                    .coerceAtLeast(1.0)
                total / 1024.0 / elapsedMs * 1_000.0
            }
        } catch (t: Throwable) {
            -1.0
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private suspend fun download(
        mirror: Mirror,
        zip: File,
        onProgress: (Int, DownloadMetrics) -> Unit
    ) {
        val connection = (URL(mirror.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NestSpace/1.0")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw IOException(AppLanguage.tr("下载失败：HTTP $code", "Download failed: HTTP $code"))
            val total = connection.contentLengthLong
            if (total > MAX_DOWNLOAD_BYTES) {
                throw IOException(
                    AppLanguage.tr("语音模型文件过大", "The speech model file is too large")
                )
            }
            zip.parentFile?.mkdirs()
            connection.inputStream.buffered().use { input ->
                zip.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPercent = -1
                    val startedAt = System.nanoTime()
                    var lastReportAt = startedAt
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded > MAX_DOWNLOAD_BYTES) {
                            throw IOException(
                                AppLanguage.tr(
                                    "语音模型文件过大",
                                    "The speech model file is too large"
                                )
                            )
                        }
                        val percent = if (total > 0) {
                            ((downloaded * 100L) / total).toInt().coerceIn(0, 99)
                        } else {
                            0
                        }
                        val now = System.nanoTime()
                        if (percent != lastPercent ||
                            now - lastReportAt >= METRICS_REPORT_INTERVAL_NS
                        ) {
                            val elapsedSeconds =
                                ((now - startedAt) / 1_000_000_000.0)
                                    .coerceAtLeast(0.001)
                            val bytesPerSecond =
                                (downloaded / elapsedSeconds).toLong()
                            val remainingSeconds = if (
                                total > 0 && bytesPerSecond > 0
                            ) {
                                val remainingBytes =
                                    (total - downloaded).coerceAtLeast(0L)
                                (remainingBytes + bytesPerSecond - 1L) /
                                    bytesPerSecond
                            } else {
                                -1L
                            }
                            lastPercent = percent
                            lastReportAt = now
                            onProgress(
                                percent,
                                DownloadMetrics(
                                    bytesPerSecond = bytesPerSecond,
                                    remainingSeconds = remainingSeconds
                                )
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extract(zip: File, target: File) {
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        val root = target.canonicalFile
        var totalBytes = 0L
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                // The published archives contain a single top-level model dir.
                val relative = name.substringAfter('/', missingDelimiterValue = "")
                if (relative.isNotBlank()) {
                    val outFile = File(target, relative).canonicalFile
                    if (outFile != root &&
                        !outFile.path.startsWith(root.path + File.separator)
                    ) {
                        throw IOException(AppLanguage.tr("语音模型包含非法路径", "The speech model contains an invalid path"))
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                totalBytes += read
                                if (totalBytes > MAX_EXTRACTED_BYTES) {
                                    throw IOException(AppLanguage.tr("语音模型解压后过大", "The extracted speech model is too large"))
                                }
                            }
                        }
                    }
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
    }

    private const val TAG = "VoskModel"
    private const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024
    private const val MAX_EXTRACTED_BYTES = 300L * 1024 * 1024
    private const val PROBE_BYTES = 32 * 1024
    private const val MIRROR_TESTS = 3
    private const val METRICS_REPORT_INTERVAL_NS = 500_000_000L
}

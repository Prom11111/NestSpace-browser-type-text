package com.coke.nestspace.plugin

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.coke.nestspace.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads debug plugins compiled directly into the host APK.
 *
 * Source plugins do not pass through package staging, DEX generation or
 * DexClassLoader. Their Gradle modules namespace assets below
 * assets/plugins-dev/<plugin-id>, which prevents manifests, icons and pages
 * from different plugins from colliding.
 */
internal object PluginSourceLoader {
    private const val TAG = "PluginSourceLoader"
    private const val ASSET_ROOT = "plugins-dev"
    private const val MAX_SOURCE_ASSETS = 2_000
    private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024

    private val restored = AtomicBoolean(false)

    @Synchronized
    fun restoreBundled(context: Context): List<PluginManifest> {
        if (!BuildConfig.DEBUG) return emptyList()
        if (restored.get()) return PluginSourceRegistry.manifests()

        val applicationContext = context.applicationContext
        PluginSourceRegistry.clear()
        val root = File(
            applicationContext.filesDir,
            "plugins/source"
        ).apply { mkdirs() }
        val lockFile = File(root, ".source-assets.lock")
        val marker = sourceBuildMarker(applicationContext)
        val markerFile = File(root, ".build-marker")

        RandomAccessFile(lockFile, "rw").use { lock ->
            lock.channel.use { channel ->
                channel.lock().use {
                    if (needsExtraction(
                            assets = applicationContext.assets,
                            root = root,
                            markerFile = markerFile,
                            marker = marker
                        )
                    ) {
                        extractAll(applicationContext.assets, root)
                        markerFile.writeText(marker)
                    }
                }
            }
        }

        val manifests = root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.sortedBy { it.name }
            ?.mapNotNull { directory ->
                runCatching {
                    attach(applicationContext, directory)
                }.onFailure {
                    Log.w(
                        TAG,
                        "source plugin restore failed: ${directory.name}",
                        it
                    )
                }.getOrNull()
            }
            .orEmpty()
        restored.set(true)
        Log.i(TAG, "source plugins restored: ${manifests.size}")
        return manifests
    }

    @Synchronized
    fun setEnabled(
        context: Context,
        pluginId: String,
        enabled: Boolean
    ): PluginManifest {
        val existing = PluginSourceRegistry.record(pluginId)
            ?: error("Source plugin is not loaded: $pluginId")
        if (enabled) {
            if (existing.plugin != null && existing.host != null) {
                return existing.manifest
            }
            return loadRecord(
                context.applicationContext,
                existing.directory,
                existing.manifest
            ).manifest
        }
        if (existing.plugin != null && existing.host != null) {
            PluginSourceRegistry.register(
                existing.copy(plugin = null, host = null)
            )
            Log.i(TAG, "source plugin disabled: $pluginId")
        }
        return existing.manifest
    }

    @Synchronized
    fun createShareFile(context: Context, pluginId: String): File {
        val manifest = PluginSourceRegistry.manifests()
            .firstOrNull { it.id == pluginId }
            ?: error("Source plugin is not loaded")
        val directory = File(
            context.cacheDir,
            "plugin-shares"
        ).apply { mkdirs() }
        val safeName = "${manifest.id}-${manifest.version}"
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(directory, "$safeName.nestplugin")
        val temporary = File(directory, ".$safeName.tmp")
        temporary.delete()
        try {
            context.assets.open(sourcePackageAsset(pluginId)).use { input ->
                FileOutputStream(temporary).use { output ->
                    copyLimited(input, output, MAX_SOURCE_PACKAGE_BYTES)
                    output.fd.sync()
                }
            }
            if (target.exists() && !target.delete()) {
                error("Old source plugin package could not be replaced")
            }
            check(temporary.renameTo(target)) {
                "Source plugin package could not be finalized"
            }
            return target
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun exportToUri(context: Context, pluginId: String, target: android.net.Uri) {
        val file = createShareFile(context, pluginId)
        context.contentResolver.openOutputStream(target)?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: error("Could not open export destination")
    }

    private fun extractAll(assets: AssetManager, root: File) {
        val directories = assets.list(ASSET_ROOT)
            ?.filter { safeAssetName(it) }
            ?.sorted()
            .orEmpty()
        val expected = directories.toSet()
        root.listFiles()
            ?.filter {
                it.isDirectory &&
                    !it.name.startsWith('.') &&
                    it.name !in expected
            }
            ?.forEach(File::deleteRecursively)

        var entries = 0
        var bytes = 0L
        directories.forEach { pluginId ->
            val staging = File(root, ".$pluginId.installing")
            staging.deleteRecursively()
            staging.mkdirs()
            try {
                copyAssetDirectory(
                    assets,
                    "$ASSET_ROOT/$pluginId",
                    staging,
                    counters = { count, size ->
                        entries += count
                        bytes += size
                        require(entries <= MAX_SOURCE_ASSETS) {
                            "source plugin assets contain too many files"
                        }
                        require(bytes <= MAX_SOURCE_BYTES) {
                            "source plugin assets exceed 64 MB"
                        }
                    }
                )
                val manifestFile = File(staging, "manifest.json")
                require(manifestFile.isFile) {
                    "source plugin $pluginId is missing manifest.json"
                }
                val manifest = PluginManifestParser.parse(
                    manifestFile.readText()
                )
                require(manifest.id == pluginId) {
                    "source asset directory $pluginId does not match manifest id " +
                        manifest.id
                }
                val target = File(root, pluginId)
                if (target.exists()) target.deleteRecursively()
                check(staging.renameTo(target)) {
                    "source plugin assets could not be finalized: $pluginId"
                }
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }
    }

    /**
     * AssetManager does not expose file metadata, so the callback receives one
     * entry and bytes copied for a file, or zeroes for a directory.
     */
    private fun copyAssetDirectory(
        assets: AssetManager,
        assetPath: String,
        target: File,
        counters: (entries: Int, bytes: Long) -> Unit
    ) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            var copied = 0L
            assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_SOURCE_BYTES) {
                            "source plugin asset is too large: $assetPath"
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            counters(1, copied)
            return
        }
        require(target.mkdirs() || target.isDirectory) {
            "could not create source plugin asset directory"
        }
        counters(0, 0)
        children.forEach { name ->
            require(safeAssetName(name)) {
                "unsafe source plugin asset name: $name"
            }
            copyAssetDirectory(
                assets = assets,
                assetPath = "$assetPath/$name",
                target = File(target, name),
                counters = counters
            )
        }
    }

    private fun attach(context: Context, directory: File): PluginManifest {
        val manifestFile = File(directory, "manifest.json")
        require(manifestFile.isFile) {
            "source plugin ${directory.name} is missing manifest.json"
        }
        val manifest = PluginManifestParser.parse(manifestFile.readText())
        require(manifest.id == directory.name) {
            "source plugin directory does not match manifest id"
        }
        if (!PluginCenterStore.isEnabled(context, manifest.id)) {
            PluginSourceRegistry.register(
                SourcePluginRecord(manifest, directory, null, null)
            )
            Log.i(TAG, "source plugin restored disabled: ${manifest.id}")
            return manifest
        }
        return loadRecord(context, directory, manifest).manifest
    }

    private fun loadRecord(
        context: Context,
        directory: File,
        manifest: PluginManifest
    ): SourcePluginRecord {
        val plugin = Class.forName(
            manifest.entryClass,
            true,
            context.classLoader
        ).getDeclaredConstructor().newInstance()
        val host = AndroidPluginHostApi(
            context,
            manifest,
            manifest.capabilities,
            manifest.httpHosts,
            manifest.webSocketHosts
        )
        PluginRuntimeManager.attach(plugin, host)
        val record = SourcePluginRecord(manifest, directory, plugin, host)
        PluginSourceRegistry.register(record)
        Log.i(
            TAG,
            "source plugin attached: ${manifest.id}@${manifest.version}"
        )
        return record
    }

    private fun sourceBuildMarker(context: Context): String {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            0
        )
        return packageInfo.lastUpdateTime.toString()
    }

    private fun needsExtraction(
        assets: AssetManager,
        root: File,
        markerFile: File,
        marker: String
    ): Boolean {
        if (!markerFile.isFile ||
            markerFile.readText().trim() != marker
        ) {
            return true
        }
        return assets.list(ASSET_ROOT)
            ?.filter(::safeAssetName)
            ?.any { pluginId ->
                !File(root, "$pluginId/manifest.json").isFile
            }
            ?: true
    }

    private fun safeAssetName(name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            '/' !in name &&
            '\\' !in name &&
            '\u0000' !in name

    private fun sourcePackageAsset(pluginId: String): String {
        require(pluginId.matches(Regex("[a-z0-9][a-z0-9._-]{2,127}"))) {
            "invalid source plugin id"
        }
        return "source-plugin-packages/$pluginId.nestplugin"
    }

    private fun copyLimited(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        limit: Long
    ) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) {
                "source plugin package exceeds 32 MB"
            }
            output.write(buffer, 0, count)
        }
    }

    private const val MAX_SOURCE_PACKAGE_BYTES = 32L * 1024 * 1024
}

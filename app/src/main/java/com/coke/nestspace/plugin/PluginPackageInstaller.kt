package com.coke.nestspace.plugin

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Development installer for signed .nestplugin packages.
 *
 * Production installation must add publisher signatures, trust levels and an
 * isolated process. This implementation validates paths, sizes, Manifest,
 * API version and class loading before attaching the plugin.
 */
internal class PluginPackageInstaller private constructor(
    private val context: Context
) {
    private data class LoadedPlugin(
        val directory: File,
        val manifest: PluginManifest,
        val plugin: Any,
        val host: AndroidPluginHostApi
    )

    private val installed = ConcurrentHashMap<String, LoadedPlugin>()
    private val packageRoot = File(
        context.filesDir,
        "plugins/packages"
    ).apply { mkdirs() }
    private val incomingRoot = File(
        context.filesDir,
        "plugins/incoming"
    ).apply { mkdirs() }

    @Synchronized
    fun restoreInstalled() {
        packageRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { directory ->
                if (installed.containsKey(directory.name)) return@forEach
                val manifestFile = File(directory, "manifest.json")
                if (!manifestFile.isFile) return@forEach
                runCatching {
                    val manifest = PluginManifestParser.parse(
                        manifestFile.readText()
                    )
                    if (PluginSourceRegistry.contains(manifest.id)) {
                        Log.i(
                            TAG,
                            "skip external plugin shadowed by source: " +
                                manifest.id
                        )
                        return@forEach
                    }
                    attach(directory, manifest)
                }.onFailure {
                    Log.w(TAG, "restore plugin failed: ${directory.name}", it)
                }
            }
    }

    @Synchronized
    fun importIncomingPackages() {
        incomingRoot.listFiles()
            ?.filter { it.isFile && it.extension.equals("nestplugin", true) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                runCatching {
                    installPackageFile(file)
                    file.delete()
                }.onFailure {
                    Log.w(TAG, "incoming plugin install failed: ${file.name}", it)
                }
            }
    }

    @Synchronized
    fun installFromUri(uri: Uri): PluginManifest {
        val temporary = File.createTempFile(
            "nestspace-plugin-",
            ".nestplugin",
            context.cacheDir
        )
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    copyLimited(input, output, MAX_PACKAGE_BYTES)
                }
            } ?: error("Could not open plugin package")
            installPackageFile(temporary)
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun installedManifests(): List<PluginManifest> =
        installed.values
            .map { it.manifest }
            .filterNot { PluginSourceRegistry.contains(it.id) }
            .sortedBy { it.name.lowercase() }

    @Synchronized
    fun htmlPage(pluginId: String): File? {
        val record = installed.values.firstOrNull {
            it.manifest.id == pluginId
        } ?: return null
        if (record.manifest.htmlPage.isBlank()) return null
        return safePackageFile(record.directory, record.manifest.htmlPage)
    }

    @Synchronized
    fun iconFile(pluginId: String): File? {
        val record = installed.values.firstOrNull {
            it.manifest.id == pluginId
        } ?: return null
        if (record.manifest.iconPath.isBlank()) return null
        return safePackageFile(record.directory, record.manifest.iconPath)
    }

    @Synchronized
    fun settingsSchemaFile(pluginId: String): File? {
        val record = installed.values.firstOrNull {
            it.manifest.id == pluginId
        } ?: return null
        val relative = record.manifest.contributions.settingsPath
        if (relative.isBlank()) return null
        return safePackageFile(record.directory, relative)
    }

    @Synchronized
    fun exportToUri(pluginId: String, target: Uri): PluginManifest {
        val record = installed.values.firstOrNull {
            it.manifest.id == pluginId
        } ?: error("Plugin is not installed")
        val temporary = File.createTempFile(
            "nestspace-export-",
            ".nestplugin",
            context.cacheDir
        )
        try {
            createArchive(record, temporary)
            context.contentResolver.openOutputStream(target)?.use { output ->
                temporary.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("Could not open export destination")
            return record.manifest
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun createShareFile(pluginId: String): File {
        val record = installed.values.firstOrNull {
            it.manifest.id == pluginId
        } ?: error("Plugin is not installed")
        val directory = File(context.cacheDir, "plugin-shares").apply {
            mkdirs()
        }
        val safeName = "${record.manifest.id}-${record.manifest.version}"
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(directory, "$safeName.nestplugin")
        val temporary = File(directory, ".$safeName.tmp")
        temporary.delete()
        try {
            createArchive(record, temporary)
            if (target.exists() && !target.delete()) {
                error("Old shared plugin package could not be replaced")
            }
            check(temporary.renameTo(target)) {
                "Shared plugin package could not be finalized"
            }
            return target
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun uninstall(pluginId: String) {
        require(!PluginSourceRegistry.contains(pluginId)) {
            "Source plugins are removed by changing the Debug source build"
        }
        val record = installed.values.firstOrNull {
            it.manifest.id == pluginId
        }
        if (record != null) {
            PluginRuntimeManager.detach(record.plugin, record.host)
            installed.remove(record.directory.name)
            check(record.directory.deleteRecursively()) {
                "Plugin package could not be deleted"
            }
        } else {
            val directory = File(packageRoot, digest(pluginId))
            if (directory.exists()) {
                check(directory.deleteRecursively()) {
                    "Plugin package could not be deleted"
                }
            }
        }

        val namespace = PluginFileStore.storageNamespace(pluginId)
        File(context.filesDir, "plugins/$namespace").deleteRecursively()
        File(context.codeCacheDir, "plugins/$namespace").deleteRecursively()
        removeNamespace(
            context.getSharedPreferences(
                "plugin_settings",
                Context.MODE_PRIVATE
            ),
            namespace
        )
        removeNamespace(
            context.getSharedPreferences(
                "plugin_secrets",
                Context.MODE_PRIVATE
            ),
            namespace
        )
        removeNamespace(
            context.getSharedPreferences(
                "plugin_cache",
                Context.MODE_PRIVATE
            ),
            namespace
        )
        context.getSharedPreferences("plugin_center", Context.MODE_PRIVATE)
            .edit()
            .remove("display_name:$pluginId")
            .apply()
        Log.i(TAG, "plugin uninstalled: $pluginId")
    }

    @Synchronized
    private fun installPackageFile(packageFile: File): PluginManifest {
        require(packageFile.isFile) { "Plugin package does not exist" }
        require(packageFile.length() <= MAX_PACKAGE_BYTES) {
            "Plugin package exceeds 32 MB"
        }
        ZipFile(packageFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= MAX_ENTRIES) { "Plugin package has too many entries" }
            var totalBytes = 0L
            val names = HashSet<String>()
            entries.forEach { entry ->
                require(entry.isDirectory || names.add(entry.name)) {
                    "Duplicate plugin entry: ${entry.name}"
                }
                require(entry.size < 0 || entry.size <= MAX_ENTRY_BYTES) {
                    "Plugin entry is too large: ${entry.name}"
                }
                totalBytes += entry.size.coerceAtLeast(0)
                require(totalBytes <= MAX_UNCOMPRESSED_BYTES) {
                    "Plugin package expands beyond 64 MB"
                }
                requireSafeEntryName(entry.name)
            }
            require(names.contains("manifest.json")) {
                "Plugin package is missing manifest.json"
            }
            require(names.contains("classes.dex")) {
                "Plugin package is missing classes.dex"
            }

            val manifestText = zip.getInputStream(
                zip.getEntry("manifest.json")
            ).use { input ->
                String(
                    readLimited(input, MAX_MANIFEST_CHARS),
                    Charsets.UTF_8
                )
            }
            val manifest = PluginManifestParser.parse(manifestText)
            require(!PluginSourceRegistry.contains(manifest.id)) {
                "Plugin ${manifest.id} is already provided by the Debug source build"
            }
            val namespace = digest(manifest.id)

            installed[namespace]?.let { existing ->
                PluginRuntimeManager.detach(existing.plugin, existing.host)
                installed.remove(namespace)
            }

            val target = File(packageRoot, namespace)
            val staging = File(packageRoot, ".$namespace.installing")
            staging.deleteRecursively()
            staging.mkdirs()
            try {
                entries.filterNot { it.isDirectory }.forEach { entry ->
                    val output = File(staging, entry.name).canonicalFile
                    require(output.path.startsWith(staging.canonicalPath + File.separator)) {
                        "Plugin entry escapes package directory: ${entry.name}"
                    }
                    output.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(output).use { outputStream ->
                            copyLimited(input, outputStream, MAX_ENTRY_BYTES)
                        }
                    }
                }
                if (target.exists()) target.deleteRecursively()
                check(staging.renameTo(target)) {
                    "Plugin package could not be finalized"
                }
                return attach(target, manifest)
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }
    }

    private fun attach(
        directory: File,
        manifest: PluginManifest
    ): PluginManifest {
        val dexPath = File(directory, "classes.dex")
        require(dexPath.isFile) { "classes.dex is missing" }
        val codeArchive = File(directory, "code.jar")
        if (!codeArchive.isFile || codeArchive.lastModified() < dexPath.lastModified()) {
            ZipOutputStream(codeArchive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                dexPath.inputStream().use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }
        }
        check(codeArchive.setReadOnly() || !codeArchive.canWrite()) {
            "Plugin code archive must be read-only"
        }
        val optimized = File(
            context.codeCacheDir,
            "plugins/${digest(manifest.id)}"
        ).apply { mkdirs() }
        val loader = dalvik.system.DexClassLoader(
            codeArchive.path,
            optimized.path,
            null,
            context.classLoader
        )
        val plugin = loader.loadClass(manifest.entryClass)
            .getDeclaredConstructor()
            .newInstance()
        val host = AndroidPluginHostApi(
            context,
            manifest,
            manifest.capabilities,
            manifest.httpHosts,
            manifest.webSocketHosts
        )
        PluginRuntimeManager.attach(plugin, host)
        installed[directory.name] = LoadedPlugin(
            directory,
            manifest,
            plugin,
            host
        )
        Log.i(
            TAG,
            "plugin attached: ${manifest.id}@${manifest.version}"
        )
        return manifest
    }

    private fun requireSafeEntryName(name: String) {
        val normalized = name.replace('\\', '/')
        require(normalized.isNotBlank()) { "Empty plugin entry" }
        require(!normalized.startsWith('/')) {
            "Absolute plugin entry: $name"
        }
        require(normalized.split('/').none { it == ".." }) {
            "Parent traversal in plugin entry: $name"
        }
        require('\u0000' !in normalized) {
            "Invalid plugin entry: $name"
        }
    }

    private fun safePackageFile(directory: File, relativePath: String): File? {
        val rootPath = directory.canonicalPath + File.separator
        val file = File(directory, relativePath).canonicalFile
        if (!file.path.startsWith(rootPath) || !file.isFile) return null
        return file
    }

    private fun createArchive(record: LoadedPlugin, outputFile: File) {
        val root = record.directory.canonicalFile
        require(
            root.walkTopDown().any {
                it.isFile && it.name == "classes.dex"
            }
        ) { "Plugin package has no classes.dex" }
        ZipOutputStream(
            BufferedOutputStream(FileOutputStream(outputFile))
        ).use { zip ->
            val rootPath = root.path + File.separator
            root.walkTopDown()
                .filter { it.isFile }
                .filterNot {
                    val relative = it.relativeTo(root)
                        .invariantSeparatorsPath
                    relative == "code.jar" ||
                        relative.startsWith("oat/") ||
                        relative.endsWith(".prof") ||
                        relative.split('/').any { part ->
                            part.startsWith('.')
                        }
                }
                .sortedBy {
                    it.relativeTo(root)
                        .invariantSeparatorsPath
                }
                .forEach { file ->
                    val canonical = file.canonicalFile
                    require(canonical.path.startsWith(rootPath)) {
                        "Plugin export escaped its package directory"
                    }
                    val name = canonical.relativeTo(root)
                        .invariantSeparatorsPath
                    requireSafeEntryName(name)
                    zip.putNextEntry(ZipEntry(name))
                    BufferedInputStream(canonical.inputStream()).use {
                        it.copyTo(zip)
                    }
                    zip.closeEntry()
                }
        }
        require(outputFile.length() <= MAX_PACKAGE_BYTES) {
            "Exported plugin exceeds 32 MB"
        }
    }

    private fun removeNamespace(
        preferences: android.content.SharedPreferences,
        namespace: String
    ) {
        val prefix = "$namespace:"
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(prefix) }
            .forEach(editor::remove)
        editor.commit()
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

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
            require(total <= limit) { "Plugin package content is too large" }
            output.write(buffer, 0, count)
        }
    }

    private fun readLimited(
        input: java.io.InputStream,
        maxBytes: Int
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyLimited(input, output, maxBytes.toLong())
        return output.toByteArray()
    }

    companion object {
        private const val TAG = "PluginInstaller"
        private const val MAX_PACKAGE_BYTES = 32L * 1024 * 1024
        private const val MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
        private const val MAX_ENTRIES = 1_000
        private const val MAX_MANIFEST_CHARS = 64 * 1024

        @Volatile
        private var instance: PluginPackageInstaller? = null

        fun get(context: Context): PluginPackageInstaller =
            instance ?: synchronized(this) {
                instance ?: PluginPackageInstaller(
                    context.applicationContext
                ).also { instance = it }
            }
    }
}

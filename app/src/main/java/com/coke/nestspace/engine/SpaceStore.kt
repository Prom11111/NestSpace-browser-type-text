package com.coke.nestspace.engine

import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class StoredInstance(
    val id: String,
    val createdAt: Long,
    val lastLaunchedAt: Long,
    val environmentVersion: Int = CURRENT_ENVIRONMENT_VERSION,
    val lastProbeResult: String? = null,
)

internal const val CURRENT_ENVIRONMENT_VERSION = 1
internal const val SPACE_STORE_VERSION = 4
internal const val APK_INDEX_SCHEMA_VERSION = 1

internal data class ApkFileFingerprint(
    val path: String,
    val size: Long,
    val lastModified: Long,
    val sha256: String
) {
    fun matches(file: File): Boolean =
        file.isFile &&
            file.length() == size &&
            file.lastModified() == lastModified

    fun toJson(): JSONObject = JSONObject()
        .put("path", path)
        .put("size", size)
        .put("last_modified", lastModified)
        .put("sha256", sha256)

    companion object {
        fun fromJson(json: JSONObject): ApkFileFingerprint? {
            val path = json.optString("path").takeIf { it.isNotBlank() } ?: return null
            val sha256 = json.optString("sha256").takeIf { it.isNotBlank() } ?: return null
            return ApkFileFingerprint(
                path = path,
                size = json.optLong("size"),
                lastModified = json.optLong("last_modified"),
                sha256 = sha256
            )
        }
    }
}

internal data class ApkInstallIndex(
    val schemaVersion: Int,
    val files: List<ApkFileFingerprint>,
    val nativeAbis: Set<String>,
    val descriptorCachePath: String
) {
    fun isCurrent(): Boolean =
        schemaVersion == APK_INDEX_SCHEMA_VERSION &&
            files.isNotEmpty() &&
            files.all { it.matches(File(it.path)) }

    val payloadDigests: List<String>
        get() = files.map { it.sha256 }.sorted()

    fun matchesPayload(digests: List<String>): Boolean =
        payloadDigests == digests.sorted()

    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", schemaVersion)
        .put("files", JSONArray().apply { files.forEach { put(it.toJson()) } })
        .put("native_abis", JSONArray(nativeAbis.sorted()))
        .put("descriptor_cache", descriptorCachePath)

    companion object {
        fun fromJson(json: JSONObject?): ApkInstallIndex? {
            if (json == null) return null
            val files = mutableListOf<ApkFileFingerprint>()
            json.optJSONArray("files")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)
                        ?.let(ApkFileFingerprint::fromJson)
                        ?.let(files::add)
                }
            }
            val abis = mutableSetOf<String>()
            json.optJSONArray("native_abis")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optString(index)
                        .takeIf { it.isNotBlank() }
                        ?.let(abis::add)
                }
            }
            val cachePath = json.optString("descriptor_cache")
                .takeIf { it.isNotBlank() }
                ?: return null
            if (files.isEmpty()) return null
            return ApkInstallIndex(
                schemaVersion = json.optInt("schema_version"),
                files = files,
                nativeAbis = abis,
                descriptorCachePath = cachePath
            )
        }
    }
}

internal data class StoredApp(
    val spaceId: String,
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val launcherActivity: String,
    val baseApkPath: String,
    val splitApkPaths: List<String>,
    val signatureSha256: String,
    val hasNativeLibs: Boolean,
    val installedAt: Long,
    val updatedAt: Long,
    val instances: List<StoredInstance>,
    val apkIndex: ApkInstallIndex? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("space_id", spaceId)
        .put("package", packageName)
        .put("label", label)
        .put("version_name", versionName ?: JSONObject.NULL)
        .put("version_code", versionCode)
        .put("launcher_activity", launcherActivity)
        .put("base_apk", baseApkPath)
        .put("split_apks", JSONArray(splitApkPaths))
        .put("signature_sha256", signatureSha256)
        .put("native_libs", hasNativeLibs)
        .put("installed_at", installedAt)
        .put("updated_at", updatedAt)
        .put("apk_index", apkIndex?.toJson() ?: JSONObject.NULL)
        .put("instances", JSONArray().apply {
            instances.forEach { instance ->
                put(
                    JSONObject()
                        .put("id", instance.id)
                        .put("created_at", instance.createdAt)
                        .put("last_launched_at", instance.lastLaunchedAt)
                        .put("environment_version", instance.environmentVersion)
                        .put("last_probe_result", instance.lastProbeResult ?: JSONObject.NULL)
                )
            }
        })

    companion object {
        fun fromJson(json: JSONObject): StoredApp? {
            val packageName = json.optString("package").takeIf { it.isNotBlank() } ?: return null
            val baseApk = json.optString("base_apk").takeIf { it.isNotBlank() } ?: return null
            val launcher = json.optString("launcher_activity").takeIf { it.isNotBlank() } ?: return null
            val splits = mutableListOf<String>()
            json.optJSONArray("split_apks")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.isNotBlank() }?.let(splits::add)
                }
            }
            val instances = mutableListOf<StoredInstance>()
            json.optJSONArray("instances")?.let { array ->
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    instances.add(
                        StoredInstance(
                            id = id,
                            createdAt = item.optLong("created_at"),
                            lastLaunchedAt = item.optLong("last_launched_at"),
                            environmentVersion = item.optInt(
                                "environment_version",
                                CURRENT_ENVIRONMENT_VERSION
                            ),
                            lastProbeResult = item.optString("last_probe_result")
                                .takeIf { it.isNotBlank() && it != "null" },
                        )
                    )
                }
            }
            return StoredApp(
                spaceId = json.optString("space_id", "virtual-1"),
                packageName = packageName,
                label = json.optString("label", packageName),
                versionName = json.optString("version_name").takeIf {
                    it.isNotBlank() && it != "null"
                },
                versionCode = json.optLong("version_code"),
                launcherActivity = launcher,
                baseApkPath = baseApk,
                splitApkPaths = splits,
                signatureSha256 = json.optString("signature_sha256"),
                hasNativeLibs = json.optBoolean("native_libs"),
                installedAt = json.optLong("installed_at"),
                updatedAt = json.optLong("updated_at"),
                instances = instances,
                apkIndex = ApkInstallIndex.fromJson(json.optJSONObject("apk_index"))
            )
        }
    }
}

internal class SpaceStore(private val file: File) {

    private val atomicFile = AtomicFile(file)

    fun load(): List<StoredApp> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val text = atomicFile.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
            val root = JSONObject(text)
            val array = root.optJSONArray("apps") ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let(StoredApp::fromJson)?.let(::add)
                }
            }
        }.onFailure {
            Log.w("SpaceStore", "load failed: ${file.absolutePath}", it)
        }.getOrDefault(emptyList())
    }

    fun save(apps: Collection<StoredApp>) {
        file.parentFile?.mkdirs()
        val root = JSONObject()
            .put("version", SPACE_STORE_VERSION)
            .put("apps", JSONArray().apply { apps.forEach { put(it.toJson()) } })
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (t: Throwable) {
            atomicFile.failWrite(output)
            throw t
        }
    }
}

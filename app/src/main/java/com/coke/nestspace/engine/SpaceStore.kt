package com.coke.nestspace.engine

import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class StoredInstance(
    val id: String,
    val createdAt: Long,
    val lastLaunchedAt: Long
)

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
    val instances: List<StoredInstance>
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
        .put("instances", JSONArray().apply {
            instances.forEach { instance ->
                put(
                    JSONObject()
                        .put("id", instance.id)
                        .put("created_at", instance.createdAt)
                        .put("last_launched_at", instance.lastLaunchedAt)
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
                            lastLaunchedAt = item.optLong("last_launched_at")
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
                instances = instances
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
            .put("version", 1)
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

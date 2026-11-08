package com.coke.nestspace.container.vpm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Persistent virtual permission overrides, stored per target package.
 *
 * Platform semantics: permissions requested in the manifest are granted at
 * install time unless they are dangerous, in which case the user decides at
 * runtime. A virtual install is user-initiated, so declared permissions are
 * granted by default; this store records explicit runtime decisions and
 * grants/revocations made from the manager UI or an agent tool.
 */
object ContainerPermissionStore {

    private const val TAG = "ContainerPermissionStore"
    const val DEFAULT_INSTANCE_ID = "default"

    data class State(
        val granted: Set<String> = emptySet(),
        val denied: Set<String> = emptySet()
    )

    fun load(
        context: Context,
        packageName: String,
        instanceId: String = DEFAULT_INSTANCE_ID
    ): State {
        val file = stateFile(context, packageName, instanceId)
        if (!file.isFile) return State()
        return runCatching {
            val json = JSONObject(file.readText())
            State(
                granted = json.optJSONArray("granted").toStringSet(),
                denied = json.optJSONArray("denied").toStringSet()
            )
        }.getOrElse {
            Log.w(TAG, "load permission state failed: ${file.absolutePath}", it)
            State()
        }
    }

    fun save(
        context: Context,
        packageName: String,
        instanceId: String = DEFAULT_INSTANCE_ID,
        state: State
    ) {
        runCatching {
            val file = stateFile(context, packageName, instanceId)
            file.parentFile?.mkdirs()
            val json = JSONObject()
                .put("version", 1)
                .put("package", packageName)
                .put("instance", instanceId)
                .put("granted", JSONArray(state.granted.toList()))
                .put("denied", JSONArray(state.denied.toList()))
            // Write-then-rename so a reader in another process never sees a
            // half-written permission file.
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                temp.delete()
                file.writeText(json.toString())
            }
        }.onFailure { Log.w(TAG, "save permission state failed", it) }
    }

    @Synchronized
    fun grant(
        context: Context,
        packageName: String,
        permission: String,
        instanceId: String = DEFAULT_INSTANCE_ID
    ): State =
        mutate(context, packageName, instanceId) { state ->
            State(
                granted = state.granted + permission,
                denied = state.denied - permission
            )
        }

    @Synchronized
    fun revoke(
        context: Context,
        packageName: String,
        permission: String,
        instanceId: String = DEFAULT_INSTANCE_ID
    ): State =
        mutate(context, packageName, instanceId) { state ->
            State(
                granted = state.granted - permission,
                denied = state.denied + permission
            )
        }

    /** Applies a grant/deny delta atomically across processes. */
    @Synchronized
    fun update(
        context: Context,
        packageName: String,
        instanceId: String = DEFAULT_INSTANCE_ID,
        granted: Set<String>,
        denied: Set<String>
    ): State = mutate(context, packageName, instanceId) { state ->
        State(
            granted = state.granted + granted - denied,
            denied = state.denied + denied - granted
        )
    }

    /**
     * The main process (manager UI/agent tools) and slot processes (target
     * runtime) share this file, so a process-local monitor is not enough.
     * Serialize the whole load-modify-save under an OS file lock as well.
     */
    @Synchronized
    private fun mutate(
        context: Context,
        packageName: String,
        instanceId: String,
        transform: (State) -> State
    ): State {
        val folder = File(context.filesDir, "vspace/perms").apply { mkdirs() }
        val lockFile = File(folder, "${scopeName(packageName, instanceId)}.lock")
        return runCatching {
            RandomAccessFile(lockFile, "rw").use { raf ->
                val lock = raf.channel.lock()
                try {
                    val next = transform(load(context, packageName, instanceId))
                    save(context, packageName, instanceId, next)
                    next
                } finally {
                    lock.release()
                }
            }
        }.onFailure {
            Log.w(TAG, "update permission state failed: $packageName@$instanceId", it)
        }.getOrElse {
            load(context, packageName, instanceId)
        }
    }

    fun clear(
        context: Context,
        packageName: String,
        instanceId: String = DEFAULT_INSTANCE_ID
    ) {
        runCatching { stateFile(context, packageName, instanceId).delete() }
    }

    /** Removes permission state for every instance of a package. */
    fun clearPackage(context: Context, packageName: String) {
        val folder = File(context.filesDir, "vspace/perms")
        val prefix = "${sanitize(packageName)}@"
        folder.listFiles()?.forEach { file ->
            if (file.name == "${sanitize(packageName)}.json" ||
                file.name.startsWith(prefix)
            ) {
                runCatching { file.delete() }
            }
        }
    }

    private fun stateFile(
        context: Context,
        packageName: String,
        instanceId: String
    ): File {
        val folder = File(context.filesDir, "vspace/perms")
        if (instanceId == DEFAULT_INSTANCE_ID) {
            val legacy = File(folder, "${sanitize(packageName)}.json")
            if (legacy.isFile) return legacy
        }
        return File(folder, "${scopeName(packageName, instanceId)}.json")
    }

    private fun scopeName(packageName: String, instanceId: String): String =
        "${sanitize(packageName)}@${sanitize(instanceId)}"

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        val result = linkedSetOf<String>()
        for (i in 0 until length()) {
            optString(i).takeIf { it.isNotBlank() }?.let(result::add)
        }
        return result
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

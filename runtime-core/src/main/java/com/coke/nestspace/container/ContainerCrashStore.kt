package com.coke.nestspace.container

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Persistent crash markers shared by the main process and container slots.
 *
 * A virtual app crash must not take down the desktop or other virtual apps.
 * Java-level failures are swallowed per container process by
 * [TargetCrashGuard]; native crashes and System.exit() kill a slot process.
 * Both paths leave a marker here, which the main process consumes on the next
 * launch so the UI/agent can report "已隔离并重启" instead of silently
 * presenting stale state.
 */
object ContainerCrashStore {

    private const val TAG = "ContainerCrashStore"

    data class Crash(
        val packageName: String,
        val instanceId: String,
        val reason: String,
        val timestamp: Long
    )

    fun record(
        context: Context,
        packageName: String,
        instanceId: String,
        reason: String,
        throwable: Throwable? = null
    ) {
        runCatching {
            val file = fileFor(context, packageName, instanceId)
            file.parentFile?.mkdirs()
            val json = JSONObject()
                .put("version", 1)
                .put("package", packageName)
                .put("instance", instanceId)
                .put("reason", reason.take(500))
                .put("time", System.currentTimeMillis())
            if (throwable != null) {
                json.put(
                    "exception",
                    "${throwable.javaClass.name}: ${throwable.message}".take(500)
                )
            }
            // Keep the first failure time of a burst so repeated reports do not
            // overwrite the evidence with a later secondary failure.
            val existing = read(file)
            if (existing != null &&
                System.currentTimeMillis() - existing.timestamp < DEDUPE_WINDOW_MS
            ) {
                return
            }
            file.writeText(json.toString())
            Log.w(TAG, "record crash $packageName@$instanceId: $reason")
        }.onFailure { Log.w(TAG, "record crash failed", it) }
    }

    fun consume(context: Context, packageName: String, instanceId: String): Crash? {
        val file = fileFor(context, packageName, instanceId)
        val crash = read(file) ?: return null
        runCatching { file.delete() }
        return crash
    }

    fun peek(context: Context, packageName: String, instanceId: String): Crash? =
        read(fileFor(context, packageName, instanceId))

    fun clear(context: Context, packageName: String, instanceId: String) {
        runCatching { fileFor(context, packageName, instanceId).delete() }
    }

    private fun read(file: File): Crash? {
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            Crash(
                packageName = json.optString("package"),
                instanceId = json.optString("instance"),
                reason = json.optString("reason"),
                timestamp = json.optLong("time")
            )
        }.getOrElse {
            Log.w(TAG, "read crash failed: ${file.absolutePath}", it)
            null
        }
    }

    private fun fileFor(context: Context, packageName: String, instanceId: String): File =
        File(
            File(context.filesDir, "vspace/crashes"),
            "${sanitize(packageName)}_${sanitize(instanceId)}.json"
        )

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private const val DEDUPE_WINDOW_MS = 30_000L
}

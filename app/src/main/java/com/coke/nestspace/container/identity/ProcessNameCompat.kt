package com.coke.nestspace.container.identity

import android.os.Process
import android.util.Log

/**
 * Target apps commonly decide whether they are running in their main process
 * with `packageName == Application.getProcessName()`. Inside the container the
 * real OS process is NestSpace, so that check fails and apps skip most of their
 * startup (AMap, for example, never initializes its module/map services).
 *
 * ActivityThread.currentProcessName() returns
 * ActivityThread.mBoundApplication.processName. Make it report the virtual
 * app's process for the duration of a target callback, then restore the host
 * value.
 */
object ProcessNameCompat {

    private const val TAG = "ProcessNameCompat"
    private val lock = Any()

    private val activityThreadClass: Class<*>? by lazy {
        runCatching { Class.forName("android.app.ActivityThread") }
            .onFailure { Log.w(TAG, "ActivityThread not found", it) }
            .getOrNull()
    }

    private val boundApplicationField: java.lang.reflect.Field? by lazy {
        runCatching {
            activityThreadClass?.getDeclaredField("mBoundApplication")
                ?.apply { isAccessible = true }
        }.onFailure { Log.w(TAG, "mBoundApplication not found", it) }.getOrNull()
    }

    private val processNameField: java.lang.reflect.Field? by lazy {
        runCatching {
            Class.forName("android.app.ActivityThread\$AppBindData")
                .getDeclaredField("processName")
                .apply { isAccessible = true }
        }.onFailure { Log.w(TAG, "AppBindData.processName not found", it) }.getOrNull()
    }

    fun <T> withProcessName(name: String, block: () -> T): T {
        val previous = synchronized(lock) { set(name) }
        return try {
            block()
        } finally {
            if (previous != null) {
                synchronized(lock) { set(previous) }
            }
        }
    }

    private var persistentPrevious: String? = null

    /**
     * Keeps the target process name in place for the session lifetime. Native
     * libraries and background threads consulted the process name long after
     * Application.onCreate; restoring it on the host thread makes those
     * components see an inconsistent process identity.
     */
    fun setPersistent(name: String) {
        synchronized(lock) {
            if (persistentPrevious == null) {
                persistentPrevious = set(name)
            } else {
                set(name)
            }
        }
    }

    fun restorePersistent() {
        synchronized(lock) {
            persistentPrevious?.let { set(it) }
            persistentPrevious = null
        }
    }

    private val setArgV0: java.lang.reflect.Method? by lazy {
        runCatching {
            Process::class.java
                .getDeclaredMethod("setArgV0", String::class.java)
                .apply { isAccessible = true }
        }.onFailure { Log.w(TAG, "Process.setArgV0 not found", it) }.getOrNull()
    }

    private fun set(name: String): String? {
        val threadField = boundApplicationField ?: return null
        val nameField = processNameField ?: return null
        val thread = runCatching {
            activityThreadClass?.getMethod("currentActivityThread")?.invoke(null)
        }.getOrNull() ?: return null
        return runCatching {
            val bound = threadField.get(thread) ?: return null
            val previous = nameField.get(bound) as? String
            nameField.set(bound, name)
            // Native SDKs often read /proc/self/cmdline instead of the Java
            // process name. setArgV0 updates the argv[0] the proc file exposes.
            setArgV0?.invoke(null, name)
            previous
        }.onFailure { Log.w(TAG, "set process name failed", it) }.getOrNull()
    }
}

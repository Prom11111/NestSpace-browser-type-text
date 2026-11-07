package com.coke.nestspace.container

import com.coke.nestspace.i18n.AppLanguage

/**
 * Process-wide registry for live ContainerSessions.
 *
 * A session is created by the first host Activity and shared by every host
 * Activity launched from inside the target app (including splash -> home
 * hand-offs).
 *
 * Sessions are intentionally kept until the slot process dies. Native
 * libraries and their JNI state are process-global and cannot be unloaded, so
 * destroying the session and creating a fresh ClassLoader when the user
 * reopens the same app fails the second `System.loadLibrary` with
 * "Shared library ... already opened by ClassLoader ..." (DeepSeek's MMKV
 * does this during Application.onCreate). Keeping the session also matches
 * Android: finishing the last Activity does not recreate the Application,
 * and the next launch continues with the same process state.
 */
object ContainerSessions {

    private data class Entry(val session: ContainerSession, var refs: Int)

    private val entries = HashMap<String, Entry>()

    /**
     * One target app per process. Native libraries cannot be unloaded and JNI
     * global state is process-wide, so hosting a second app here would let its
     * .so files alias the first app's (same file name -> already-loaded lib).
     * The launch path assigns a dedicated slot process per session; this guard
     * turns a slot-allocation bug into a visible error instead of silent
     * native-state corruption.
     */
    @Volatile
    private var processOwnerSession: String? = null

    @Synchronized
    fun acquire(host: android.content.Context, spec: ContainerLaunchSpec): ContainerSession {
        val owner = processOwnerSession
        if (owner != null && owner != spec.sessionId) {
            error(
                AppLanguage.tr(
                    "该容器进程已被 $owner 占用；为避免 .so/JNI 状态混淆，" +
                        "同一进程不能加载第二个虚拟应用",
                    "This container process is already hosting $owner. To avoid " +
                        ".so/JNI state conflicts, one process cannot load a second " +
                        "virtual app."
                )
            )
        }
        processOwnerSession = spec.sessionId

        val existing = entries[spec.sessionId]
        if (existing != null) {
            existing.session.activateProcessIdentity()
            retainLocked(spec.sessionId)
            return existing.session
        }
        val session = ContainerSession.create(host, spec)
        entries[spec.sessionId] = Entry(session, 1)
        return session
    }

    @Synchronized
    fun retain(sessionId: String): ContainerSession? {
        val entry = entries[sessionId] ?: return null
        entry.session.activateProcessIdentity()
        retainLocked(sessionId)
        return entry.session
    }

    private fun retainLocked(sessionId: String) {
        entries[sessionId]?.let { it.refs++ }
    }

    @Synchronized
    fun release(sessionId: String) {
        val entry = entries[sessionId] ?: return
        entry.refs = (entry.refs - 1).coerceAtLeast(0)
        if (entry.refs == 0) {
            entry.session.suspendProcessIdentity()
        }
    }

    /**
     * Runs host Activity framework initialization under NestSpace's process
     * identity. The session is reactivated explicitly only when target startup
     * begins, so the framework can finish constructing host lifecycle
     * resources without seeing the target application icon/package.
     */
    fun <T> withHostProcessIdentity(block: () -> T): T {
        val session = synchronized(this) {
            processOwnerSession?.let { entries[it]?.session }
        }
        return session?.withHostProcessIdentity(block) ?: block()
    }

    fun restoreHostFrameworkState(
        activity: android.app.Activity,
        restoreTheme: Boolean
    ) {
        val session = synchronized(this) {
            processOwnerSession?.let { entries[it]?.session }
        }
        session?.attachHostFrameworkState(activity, restoreTheme)
    }
}

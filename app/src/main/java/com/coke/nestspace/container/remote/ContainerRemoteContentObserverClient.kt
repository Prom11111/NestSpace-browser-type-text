package com.coke.nestspace.container.remote

import android.net.Uri
import android.util.Log
import com.coke.nestspace.container.ContainerProcessRouter
import com.coke.nestspace.container.ContainerSession
import java.util.concurrent.Executors

/**
 * Forwards a virtual ContentProvider change to other target processes that
 * are already running. Provider registrations are process-local, so a write
 * in ":worker" must wake observers registered by the target's main process.
 */
object ContainerRemoteContentObserverClient {
    private const val TAG = "ContainerRemoteObserver"
    private val io = Executors.newCachedThreadPool()

    fun send(session: ContainerSession, uris: Collection<Uri>, flags: Int) {
        if (uris.isEmpty()) return
        val changed = uris.toTypedArray()
        val current = session.spec.targetProcessName
        val processes = linkedSetOf<String>().apply {
            add(session.spec.packageName)
            add(session.spec.activityInfo.processName)
            session.spec.activities.mapTo(this) { it.processName }
            session.spec.services.mapTo(this) { it.processName }
            session.spec.providers.mapTo(this) { it.processName }
            session.spec.receivers.mapTo(this) { it.processName }
        }
            .map {
                ContainerProcessRouter.normalize(session.spec.packageName, it)
            }
            .filter { it != current }
            .distinct()
        if (processes.isEmpty()) return
        io.execute {
            processes.forEach { process ->
                val slot = ContainerProcessRouter.slotForProcess(
                    session.hostContext,
                    session.spec.packageName,
                    session.spec.instanceId,
                    process
                ) ?: return@forEach
                runCatching {
                    ContainerRemoteProviderClient.notifyContentChange(
                        session,
                        process,
                        changed,
                        flags
                    )
                }.onFailure {
                    Log.w(TAG, "notify $process failed slot=$slot", it)
                }
            }
        }
    }
}

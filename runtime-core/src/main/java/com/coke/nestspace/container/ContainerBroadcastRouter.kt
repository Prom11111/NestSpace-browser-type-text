package com.coke.nestspace.container

import android.content.pm.ActivityInfo

/** Pure process routing for manifest BroadcastReceivers. */
internal object ContainerBroadcastRouter {
    fun remoteProcesses(
        packageName: String,
        currentProcess: String,
        receivers: List<ActivityInfo>
    ): List<String> = receivers.asSequence()
        .filter { it.enabled }
        .map {
            ContainerProcessRouter.normalize(packageName, it.processName)
        }
        .filter { it != currentProcess }
        .distinct()
        .toList()
}

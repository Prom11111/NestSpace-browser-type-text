package com.coke.nestspace.control

import android.content.Context

/**
 * Keeps a persistent UI-service binding for a headless container.
 *
 * The actual connection is owned by [ContainerUiClient], so tool calls reuse
 * the same binder instead of creating a second binding and repeatedly
 * binding/unbinding the service.
 */
object ContainerUiKeepAlive {

    fun bind(context: Context, slot: Int) {
        ContainerUiClient.retainConnection(context, slot)
    }

    fun unbind(context: Context, slot: Int) {
        ContainerUiClient.releaseRetainedConnection(context, slot)
    }

    fun unbindAll(context: Context) {
        ContainerUiClient.releaseAllRetainedConnections(context)
    }

    internal fun isRetained(slot: Int): Boolean =
        ContainerUiClient.isConnectionRetained(slot)
}

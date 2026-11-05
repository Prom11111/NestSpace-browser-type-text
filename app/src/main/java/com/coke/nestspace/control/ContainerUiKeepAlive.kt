package com.coke.nestspace.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a persistent UI-service binding for a headless container.
 *
 * A headless host is moved to the back after launch, so its slot process is
 * otherwise a cached process and can be reclaimed while the user is between
 * assistant turns. The UI service is normally bound only for the duration of
 * a tool call; holding the connection here keeps the slot service (and the
 * target Application/Activity) alive so a later ui_observe can read the same
 * page without relaunching the app.
 *
 * No notification is created. This is a best-effort keep-alive; the pool's
 * explicit eviction and system kills are still handled by the container
 * recovery path.
 */
object ContainerUiKeepAlive {

    private const val TAG = "ContainerKeepAlive"

    private data class Binding(
        val context: Context,
        val connection: ServiceConnection
    )

    private val bindings = ConcurrentHashMap<Int, Binding>()

    @Synchronized
    fun bind(context: Context, slot: Int) {
        if (slot !in ContainerUiClient.serviceClasses.indices) return
        if (bindings.containsKey(slot)) return
        val app = context.applicationContext
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.d(TAG, "slot $slot connected")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                dropped("disconnected")
            }

            override fun onBindingDied(name: ComponentName?) {
                dropped("binding died")
            }

            override fun onNullBinding(name: ComponentName?) {
                dropped("null binding")
            }

            private fun dropped(reason: String) {
                Log.w(TAG, "slot $slot $reason")
                synchronized(this@ContainerUiKeepAlive) {
                    if (bindings[slot]?.connection === this) {
                        bindings.remove(slot)
                        runCatching { app.unbindService(this) }
                    }
                }
            }
        }
        val bound = runCatching {
            app.bindService(
                Intent(app, ContainerUiClient.serviceClasses[slot]),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrDefault(false)
        if (bound) {
            bindings[slot] = Binding(app, connection)
        } else {
            Log.w(TAG, "slot $slot bind failed")
        }
    }

    @Synchronized
    fun unbind(context: Context, slot: Int) {
        val binding = bindings.remove(slot) ?: return
        runCatching { binding.context.unbindService(binding.connection) }
    }

    @Synchronized
    fun unbindAll(context: Context) {
        bindings.keys.toList().forEach { unbind(context, it) }
    }
}

package com.coke.nestspace.container

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection

/**
 * Routes target-package Service calls to ContainerServiceManager instead of
 * letting the host AMS reject them. External services fall through to the host.
 */
interface ContainerServiceBridge {
    fun startService(intent: Intent, foreground: Boolean): ComponentName?
    fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean
    /** Returns true when the connection belonged to this container. */
    fun unbindService(connection: ServiceConnection): Boolean
    fun stopService(intent: Intent): Boolean
}

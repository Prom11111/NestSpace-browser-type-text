package com.coke.nestspace.container

import android.content.Intent

/**
 * Bridge from a target app's Context back to the container host stack.
 *
 * `requestCode` is -1 for a plain startActivity call. Returning false means the
 * intent is not an in-package activity and should be handled by the host.
 */
fun interface ContainerNavigator {
    fun startTargetActivity(intent: Intent, requestCode: Int): Boolean
}

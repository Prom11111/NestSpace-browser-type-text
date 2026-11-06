package com.coke.nestspace.container

import android.content.Context
import android.content.ContextWrapper

/**
 * Base context for a target Activity.
 *
 * The container's root context is backed by the host application context,
 * whose `Context.isUiContext()` is false. Activities are expected to be UI
 * contexts; AndroidX WindowManager forwards the Activity to
 * `WindowLayoutComponentImpl`, which throws
 * "Context must be a UI Context with display association" before the target
 * can draw (DeepSeek 2.7.4 does this during startup).
 *
 * `getAssociatedDisplayId()` is a hidden framework method, so it cannot be
 * declared with `override`, but the JVM dispatches calls on this wrapper to it
 * all the same. `isUiContext()` is part of the public Context API.
 */
class ContainerUiContext(
    base: Context,
    private val displayId: Int
) : ContextWrapper(base) {

    override fun isUiContext(): Boolean = true

    @Suppress("unused")
    fun getAssociatedDisplayId(): Int = displayId
}

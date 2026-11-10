package com.coke.nestspace.container.activity

/**
 * A target may override onBackPressed without finishing its host or exposing
 * OnBackPressedDispatcher callbacks. The root host still needs a deterministic
 * exit path back to NestSpace instead of swallowing Back indefinitely.
 */
internal object ContainerBackPolicy {
    fun shouldReturnRootToDesktop(
        isTaskRoot: Boolean,
        hasEnabledCallbacks: Boolean
    ): Boolean = isTaskRoot && !hasEnabledCallbacks
}

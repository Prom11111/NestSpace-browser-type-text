package com.coke.nestspace.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Small in-process signal used when a container Activity closes and the
 * launcher should return to its desktop tab, regardless of which sub-page was
 * showing before the container was opened.
 */
object DesktopNavigation {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: SharedFlow<Unit> = _requests

    fun requestDesktop() {
        _requests.tryEmit(Unit)
    }
}

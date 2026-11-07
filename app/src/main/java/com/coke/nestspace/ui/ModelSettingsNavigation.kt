package com.coke.nestspace.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Opens model settings from the floating voice assistant. */
object ModelSettingsNavigation {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: SharedFlow<Unit> = _requests

    @Volatile
    private var pending = false

    fun requestModelSettings() {
        pending = true
        _requests.tryEmit(Unit)
    }

    fun consumeRequest(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }
}

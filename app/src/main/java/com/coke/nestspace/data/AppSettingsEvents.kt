package com.coke.nestspace.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-process change signal for settings edited outside the Compose screens,
 * mainly by the local MCP server. Screens collect [revision] and reload the
 * affected store so an MCP update is visible without restarting the app.
 */
object AppSettingsEvents {

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    fun notifyChanged() {
        _revision.value += 1
    }
}

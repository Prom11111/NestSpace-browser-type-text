package com.coke.nestspace.ui.agent

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.control.ContainerUiClient
import com.coke.nestspace.i18n.AppLanguage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows the confirmation / takeover decision that a tool call is waiting on.
 * [UserGateManager] posts a notification only while this host is in the
 * background, avoiding a dialog and notification for the same request.
 */
@Composable
fun UserGateHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val confirmation by UserGateManager.confirmation.collectAsState()
    val takeover by UserGateManager.takeover.collectAsState()
    TrackGateHostVisibility()

    confirmation?.let { request ->
        AlertDialog(
            onDismissRequest = {
                UserGateManager.denyConfirmation(request.id)
            },
            title = { Text(request.title) },
            text = { Text(request.detail) },
            confirmButton = {
                TextButton(
                    onClick = { UserGateManager.approveConfirmation(request.id) }
                ) {
                    Text(request.confirmLabel)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { UserGateManager.denyConfirmation(request.id) }
                ) {
                    Text(request.dismissLabel)
                }
            }
        )
    }

    takeover?.let { request ->
        AlertDialog(
            onDismissRequest = { /* The user must choose Done or Skip. */ },
            title = { Text(request.title) },
            text = { Text(request.detail) },
            confirmButton = {
                TextButton(
                    onClick = {
                        UserGateManager.completeTakeover(
                            request.id,
                            UserGateManager.TakeoverResult.COMPLETED
                        )
                    }
                ) {
                    Text(AppLanguage.tr("完成", "Done"))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (request.target == UserGateManager.TakeoverTarget.BROWSER) {
                                    UserGateManager.bringTakeoverToFront(context, request)
                                } else if (ContainerUiClient.bringToFront(context, request.slot) != true) {
                                    withContext(Dispatchers.IO) {
                                        UserGateManager.bringTakeoverToFront(context, request)
                                    }
                                }
                            }
                        }
                    ) {
                        Text(
                            if (request.target == UserGateManager.TakeoverTarget.BROWSER) {
                                AppLanguage.tr("打开浏览器", "Open browser")
                            } else {
                                AppLanguage.tr("去操作", "Open app")
                            }
                        )
                    }
                    TextButton(
                        onClick = {
                            UserGateManager.completeTakeover(
                                request.id,
                                UserGateManager.TakeoverResult.SKIPPED
                            )
                        }
                    ) {
                        Text(AppLanguage.tr("跳过", "Skip"))
                    }
                }
            }
        )
    }
}

@Composable
private fun TrackGateHostVisibility() {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var registered = false

        fun register() {
            if (!registered) {
                registered = true
                UserGateManager.onGateHostResumed()
            }
        }

        fun unregister() {
            if (registered) {
                registered = false
                UserGateManager.onGateHostPaused()
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> unregister()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            register()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }
}

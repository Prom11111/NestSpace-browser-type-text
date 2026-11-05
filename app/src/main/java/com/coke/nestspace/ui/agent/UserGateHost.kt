package com.coke.nestspace.ui.agent

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.control.ContainerUiClient
import com.coke.nestspace.i18n.AppLanguage
import kotlinx.coroutines.launch

/**
 * Shows the confirmation / takeover decision that a tool call is waiting on.
 * The same action is also available from a notification, which matters when
 * the user is inside the virtual app instead of the NestSpace UI.
 */
@Composable
fun UserGateHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val confirmation by UserGateManager.confirmation.collectAsState()
    val takeover by UserGateManager.takeover.collectAsState()

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
                                    UserGateManager.bringTakeoverToFront(context, request)
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

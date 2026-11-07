package com.coke.nestspace.ui.agent

import com.coke.nestspace.i18n.AppLanguage
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.agent.AssistantConversation
import com.coke.nestspace.assistant.FloatingDotSettings
import com.coke.nestspace.assistant.LocalSpeechModelStore
import com.coke.nestspace.agent.ToolPermissionLevel
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.ReasoningEffort
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    busy: Boolean,
    queuedSteering: Int,
    config: LlmConfig,
    configs: List<LlmConfig>,
    activeLlmId: String,
    onSelectModel: (String) -> Unit,
    onOpenSettings: () -> Unit,
    permissionLevel: ToolPermissionLevel,
    onPermissionLevelChange: (ToolPermissionLevel) -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    focusRequester: FocusRequester
) {
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var permissionMenu by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(false) }
    val canSend = input.isNotBlank()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val modelRevision by LocalSpeechModelStore.revision.collectAsState()
    val speechLanguage = remember { FloatingDotSettings.load(context).speechLanguage }
    val speechReady = remember(modelRevision, speechLanguage) {
        LocalSpeechModelStore.isReady(context, speechLanguage)
    }
    val dictation = remember { VoiceDictation(context.applicationContext) }

    fun startVoice() {
        voiceMode = true
        dictation.start { text ->
            onInputChange(text)
            voiceMode = false
        }
    }

    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoice()
        } else {
            Toast.makeText(
                context,
                AppLanguage.tr("需要麦克风权限才能语音输入", "Microphone permission is required"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun requestVoice() {
        if (!speechReady) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startVoice()
        } else {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(dictation, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                dictation.stop()
                voiceMode = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            dictation.shutdown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp)
    ) {
        if (queuedSteering > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    AppLanguage.tr(
                        "已排队 $queuedSteering 条补充，将在下一步注入",
                        "$queuedSteering follow-up message(s) queued for the next step"
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = { AssistantConversation.clearSteering() }) {
                    Text(AppLanguage.tr("清空", "Clear"))
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)
            )
        ) {
            Column(Modifier.padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box {
                        ControlChip(
                            icon = Icons.Rounded.SmartToy,
                            label = config.model.ifBlank {
                                config.name.ifBlank { AppLanguage.tr(AppLanguage.tr("选择模型", "Choose model"), "Choose model") }
                            },
                            onClick = { modelMenu = true }
                        )
                        ModelMenu(
                            expanded = modelMenu,
                            onDismiss = { modelMenu = false },
                            configs = configs,
                            activeLlmId = activeLlmId,
                            onSelect = {
                                modelMenu = false
                                onSelectModel(it)
                            },
                            onOpenSettings = {
                                modelMenu = false
                                onOpenSettings()
                            }
                        )
                    }
                    Box {
                        ControlChip(
                            icon = Icons.Rounded.Psychology,
                            label = AppLanguage.tr(
                                "思考 · ${config.reasoningEffort.label}",
                                "Reasoning · ${config.reasoningEffort.label}"
                            ),
                            onClick = { effortMenu = true }
                        )
                        ReasoningMenu(
                            expanded = effortMenu,
                            onDismiss = { effortMenu = false },
                            current = config.reasoningEffort,
                            onSelect = {
                                effortMenu = false
                                onReasoningEffortChange(it)
                            }
                        )
                    }
                    Box {
                        ControlChip(
                            icon = Icons.Rounded.Security,
                            label = AppLanguage.tr(
                                "确认 · ${permissionLevel.label}",
                                "Confirm · ${permissionLevel.label}"
                            ),
                            onClick = { permissionMenu = true }
                        )
                        PermissionMenu(
                            expanded = permissionMenu,
                            onDismiss = { permissionMenu = false },
                            current = permissionLevel,
                            onSelect = {
                                permissionMenu = false
                                onPermissionLevelChange(it)
                            },
                            onOpenSettings = {
                                permissionMenu = false
                                onOpenPermissionSettings()
                            }
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    if (voiceMode) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp, max = 150.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (dictation.preparing.value) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Mic,
                                    contentDescription = null,
                                    tint = if (dictation.listening.value) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(9.dp))
                            Text(
                                dictation.transcript.value.ifBlank {
                                    dictation.status.value
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (dictation.transcript.value.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        BasicTextField(
                            value = input,
                            onValueChange = onInputChange,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 28.dp, max = 150.dp)
                                .focusRequester(focusRequester),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = { if (canSend) onSend() }
                            ),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (input.isBlank()) {
                                        Text(
                                            AppLanguage.tr(
                                                "输入任务，或描述你想完成的事情…",
                                                "Describe what you'd like me to do…"
                                            ),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.68f)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    if (busy && input.isNotBlank()) {
                        // Keep a stop affordance even while the user has a
                        // steering draft; the main button sends it.
                        IconButton(
                            onClick = onStop,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Stop,
                                contentDescription = AppLanguage.tr("停止", "Stop"),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            when {
                                busy && input.isBlank() -> onStop()
                                voiceMode -> {
                                    val recognized = dictation.transcript.value
                                    dictation.stop()
                                    voiceMode = false
                                    if (recognized.isNotBlank()) onInputChange(recognized)
                                }
                                input.isBlank() && speechReady -> requestVoice()
                                else -> onSend()
                            }
                        },
                        enabled = voiceMode || busy || canSend ||
                            (input.isBlank() && speechReady),
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp)
                    ) {
                        when {
                            busy && input.isBlank() -> Icon(
                                Icons.Rounded.Stop,
                                contentDescription = AppLanguage.tr("停止", "Stop"),
                                modifier = Modifier.size(20.dp)
                            )
                            voiceMode -> Icon(
                                Icons.Rounded.Keyboard,
                                contentDescription = AppLanguage.tr(
                                    "切换到文字输入",
                                    "Switch to text input"
                                ),
                                modifier = Modifier.size(20.dp)
                            )
                            input.isBlank() && speechReady -> Icon(
                                Icons.Rounded.Mic,
                                contentDescription = AppLanguage.tr("语音输入", "Voice input"),
                                modifier = Modifier.size(20.dp)
                            )
                            else -> Icon(
                                Icons.Rounded.ArrowUpward,
                                contentDescription = AppLanguage.tr("发送", "Send"),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

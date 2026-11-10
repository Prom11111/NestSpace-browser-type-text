package com.coke.nestspace.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.coke.nestspace.assistant.FloatingDotOverlay
import com.coke.nestspace.assistant.FloatingDotSettings
import com.coke.nestspace.assistant.LocalSpeechEngine
import com.coke.nestspace.assistant.LocalSpeechModelStore
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.i18n.AppLanguage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Settings for the assistive dot: enable/disable, overlay permission, and the
 * two colors used over the host UI and inside virtual containers.
 */
@Composable
internal fun FloatingDotDetailScreen() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(FloatingDotSettings.load(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var showSpeechSources by remember { mutableStateOf(false) }
    val speechProgress by LocalSpeechModelStore.progress.collectAsState()
    val speechMetrics by LocalSpeechModelStore.metrics.collectAsState()
    val speechSource by LocalSpeechModelStore.source.collectAsState()
    val modelRevision by LocalSpeechModelStore.revision.collectAsState()
    val settingsRevision by AppSettingsEvents.revision.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(settingsRevision) {
        config = FloatingDotSettings.load(context)
    }
    val speechSpec = LocalSpeechModelStore.spec(config.speechLanguage)
    val speechPercent = speechProgress[config.speechLanguage]
    val speechDownloading = speechPercent != null && speechPercent in 0..99
    val speechTransfer = speechMetrics[config.speechLanguage]
    val speechDownloaded = remember(config.speechLanguage, modelRevision) {
        LocalSpeechModelStore.isReady(context, config.speechLanguage)
    }
    val engineLoadedLanguage by LocalSpeechEngine.loadedLanguage.collectAsState()
    val engineLoadingLanguage by LocalSpeechEngine.loadingLanguage.collectAsState()
    val speechLoaded = speechDownloaded &&
        engineLoadedLanguage == config.speechLanguage
    val speechLoading = speechDownloaded &&
        engineLoadingLanguage == config.speechLanguage

    fun update(next: FloatingDotSettings.Config) {
        config = next
        FloatingDotSettings.save(context, next)
        FloatingDotOverlay.reload(context)
    }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        overlayGranted = Settings.canDrawOverlays(context)
        FloatingDotOverlay.reload(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                FloatingDotOverlay.reload(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun requestOverlay() {
        overlayLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsSection(AppLanguage.tr("悬浮球", "Floating dot")) }
        item {
            SettingsCard(
                title = AppLanguage.tr("显示悬浮球", "Show floating dot"),
                status = when {
                    !config.enabled -> AppLanguage.tr("已关闭", "Off")
                    overlayGranted -> AppLanguage.tr("已开启", "On")
                    else -> AppLanguage.tr("等待悬浮窗授权", "Waiting for overlay permission")
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = AppLanguage.tr(
                            "屏幕上常驻的小圆点。点击唤起 AI 语音助手，可拖动吸边。",
                            "A small dot that stays on screen. Tap it to open the AI " +
                                "voice assistant; drag it to the edge."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { checked ->
                            update(config.copy(enabled = checked))
                            if (checked && !overlayGranted) requestOverlay()
                        }
                    )
                }
                if (config.enabled && !overlayGranted) {
                    Text(
                        text = AppLanguage.tr(
                            "需要“显示在其他应用上层”权限才能显示悬浮球。",
                            "The \"Display over other apps\" permission is required " +
                                "to show the floating dot."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedButton(onClick = { requestOverlay() }) {
                        Text(AppLanguage.tr("授权悬浮窗", "Allow overlay"))
                    }
                }
            }
        }

        item { SettingsSection(AppLanguage.tr("颜色", "Colors")) }
        item {
            SettingsCard(
                title = AppLanguage.tr("宿主界面颜色", "Host UI color"),
                status = FloatingDotSettings.colorLabel(config.hostColor)
            ) {
                Text(
                    AppLanguage.tr(
                    "浏览器、AI 语音助手和容器外应用等宿主界面上的颜色（默认橙色）。",
                    "Color over host screens such as the browser, AI voice assistant " +
                        "and apps outside the container (orange by default)."
                ),
                    style = MaterialTheme.typography.bodySmall
                )
                ColorSwatches(
                    colors = FloatingDotSettings.HOST_PRESETS,
                    selected = config.hostColor
                ) { color -> update(config.copy(hostColor = color)) }
            }
        }
        item {
            SettingsCard(
                title = AppLanguage.tr("容器内颜色", "Container color"),
                status = FloatingDotSettings.colorLabel(config.containerColor)
            ) {
                Text(
                    AppLanguage.tr(
                    "NestSpace 桌面、空间设置和容器内应用（包括全屏应用）上的颜色，默认蓝色。",
                    "Color over the NestSpace desktop, space management and virtual " +
                        "apps, including fullscreen apps (blue by default)."
                ),
                    style = MaterialTheme.typography.bodySmall
                )
                ColorSwatches(
                    colors = FloatingDotSettings.CONTAINER_PRESETS,
                    selected = config.containerColor
                ) { color -> update(config.copy(containerColor = color)) }
            }
        }

        item { SettingsSection(AppLanguage.tr("透明度", "Opacity")) }
        item {
            SettingsCard(
                title = AppLanguage.tr("悬浮球透明度", "Floating dot opacity"),
                status = "${config.opacity}%"
            ) {
                Text(
                    AppLanguage.tr(
                        "数值越低越透明，100% 为完全不透明。左右拖动滑块调整。",
                        "Lower values are more transparent; 100% is fully opaque. " +
                            "Drag the slider left or right to adjust."
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = config.opacity.toFloat(),
                    onValueChange = { value ->
                        val step = FloatingDotSettings.OPACITY_STEP
                        val next = ((value / step).roundToInt() * step)
                            .coerceIn(
                                FloatingDotSettings.MIN_OPACITY,
                                FloatingDotSettings.MAX_OPACITY
                            )
                        if (next != config.opacity) {
                            update(config.copy(opacity = next))
                        }
                    },
                    valueRange = FloatingDotSettings.MIN_OPACITY.toFloat()..
                        FloatingDotSettings.MAX_OPACITY.toFloat(),
                    steps = (FloatingDotSettings.MAX_OPACITY -
                        FloatingDotSettings.MIN_OPACITY) / FloatingDotSettings.OPACITY_STEP - 1
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${FloatingDotSettings.MIN_OPACITY}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${FloatingDotSettings.MAX_OPACITY}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { SettingsSection(AppLanguage.tr("语音", "Voice")) }
        item {
            SettingsCard(
                title = AppLanguage.tr("本地语音识别语言", "Local speech recognition language"),
                status = if (config.speechLanguage == FloatingDotSettings.SPEECH_LANG_EN) {
                    "English"
                } else {
                    "中文"
                }
            ) {
                Text(
                    AppLanguage.tr(
                    "首次使用会在设备上下载约 40MB 离线模型，识别过程完全在本地运行、不需要网络。切换语言后下次打开语音助手生效。",
                    "The first use downloads an offline model of about 40 MB. " +
                        "Recognition runs fully on device without a network. A " +
                        "language change applies the next time the voice assistant opens."
                ),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = config.speechLanguage !=
                            FloatingDotSettings.SPEECH_LANG_EN,
                        onClick = {
                            update(
                                config.copy(
                                    speechLanguage = FloatingDotSettings.SPEECH_LANG_ZH
                                )
                            )
                        },
                        label = { Text("中文") }
                    )
                    FilterChip(
                        selected = config.speechLanguage ==
                            FloatingDotSettings.SPEECH_LANG_EN,
                        onClick = {
                            update(
                                config.copy(
                                    speechLanguage = FloatingDotSettings.SPEECH_LANG_EN
                                )
                            )
                        },
                        label = { Text("English") }
                    )
                }
            }
        }

        item {
            SettingsCard(
                title = AppLanguage.tr("离线语音模型", "Offline speech model"),
                status = when {
                    speechLoaded -> AppLanguage.tr("已加载", "Loaded")
                    speechDownloading -> "${speechPercent}%"
                    speechDownloaded && speechLoading -> AppLanguage.tr("加载中…", "Loading…")
                    speechDownloaded -> AppLanguage.tr("已下载", "Downloaded")
                    speechPercent == -1 -> AppLanguage.tr("下载失败", "Download failed")
                    else -> AppLanguage.tr("未下载", "Not downloaded")
                }
            ) {
                Text(
                    AppLanguage.tr(
                        "${speechSpec.displayName} · 约 ${speechSpec.approximateMb}MB。" +
                            "打开语音助手时也会按需自动下载，下载后可完全离线识别。",
                        "${speechSpec.displayName} · about ${speechSpec.approximateMb} MB. " +
                            "It also downloads automatically when the voice assistant " +
                            "opens; after that recognition works fully offline."
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                if (speechDownloading) {
                    LinearProgressIndicator(
                        progress = { (speechPercent ?: 0).coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            AppLanguage.tr(
                                "当前线路：${speechSource[config.speechLanguage] ?: "正在选择下载线路中…"}",
                                "Current line: ${speechSource[config.speechLanguage] ?: "choosing a download line…"}"
                            ) + buildString {
                                speechTransfer?.speedText()
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { append(" · ").append(it) }
                                speechTransfer?.remainingText()
                                    ?.takeIf(String::isNotBlank)
                                    ?.let {
                                        append(
                                            AppLanguage.tr(
                                                " · 预计剩余时间 $it",
                                                " · Estimated remaining $it"
                                            )
                                        )
                                    }
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(onClick = { showSpeechSources = true }) {
                            Text(AppLanguage.tr("切换下载线路", "Switch line"))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !speechDownloading && !speechDownloaded,
                        onClick = {
                            scope.launch {
                                runCatching {
                                    LocalSpeechModelStore.ensure(
                                        context,
                                        config.speechLanguage
                                    )
                                }
                            }
                        }
                    ) {
                        Text(
                            if (speechDownloading) AppLanguage.tr("下载中…", "Downloading…")
                            else AppLanguage.tr("下载模型", "Download model")
                        )
                    }
                    if (speechDownloaded) {
                        OutlinedButton(
                            onClick = {
                                LocalSpeechModelStore.delete(
                                    context,
                                    config.speechLanguage
                                )
                            }
                        ) { Text(AppLanguage.tr("删除模型", "Delete model")) }
                    }
                }
            }
        }

        item {
            SettingsCard(title = AppLanguage.tr("预览", "Preview"), status = "") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        DotPreview(config.hostColor)
                        Text(AppLanguage.tr("宿主", "Host"), style = MaterialTheme.typography.labelMedium)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        DotPreview(config.containerColor)
                        Text(AppLanguage.tr("容器", "Container"), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    text = AppLanguage.tr(
                        "悬浮球会自动跟随当前界面切换颜色；关闭开关后立即隐藏。",
                        "The dot follows the current screen's color automatically " +
                            "and hides immediately when turned off."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showSpeechSources) {
        AlertDialog(
            onDismissRequest = { showSpeechSources = false },
            title = { Text(AppLanguage.tr("切换下载线路", "Switch download line")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    speechSpec.mirrors.forEach { mirror ->
                        OutlinedButton(
                            onClick = {
                                showSpeechSources = false
                                LocalSpeechModelStore.switchDownloadSource(
                                    context,
                                    config.speechLanguage,
                                    mirror.label
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (speechSource[config.speechLanguage] == mirror.label) {
                                    "${mirror.label}  ·  ${AppLanguage.tr("当前", "Current")}"
                                } else {
                                    mirror.label
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeechSources = false }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }
}

@Composable
private fun ColorSwatches(
    colors: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(colors) { color ->
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(
                        width = if (color == selected) 3.dp else 1.dp,
                        color = if (color == selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape
                    )
                    .clickable { onSelect(color) },
                contentAlignment = Alignment.Center
            ) {
                if (color == selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = AppLanguage.tr("已选择", "Selected"),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DotPreview(color: Int) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(2.dp, Color.White.copy(alpha = 0.55f), CircleShape)
    )
}

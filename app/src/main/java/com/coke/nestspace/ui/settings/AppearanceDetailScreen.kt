package com.coke.nestspace.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.coke.nestspace.container.ContainerIndicatorSettings
import com.coke.nestspace.i18n.AppLanguage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun AppearanceDetailScreen(
    hasWallpaper: Boolean,
    onPickWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit
) {
    val context = LocalContext.current
    var indicator by remember {
        mutableStateOf(ContainerIndicatorSettings.isEnabled(context))
    }
    var colorMode by remember {
        mutableStateOf(ContainerIndicatorSettings.colorMode(context))
    }
    var fixedColor by remember {
        mutableStateOf(ContainerIndicatorSettings.fixedColor(context))
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsSection(AppLanguage.tr("桌面", "Desktop")) }
        item {
            SettingsCard(
                title = AppLanguage.tr("桌面壁纸", "Desktop wallpaper"),
                status = if (hasWallpaper) AppLanguage.tr("已设置", "Set") else AppLanguage.tr("默认", "Default")
            ) {
                Text(
                    AppLanguage.tr("选择图片作为桌面背景。", "Choose an image as the desktop background."),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickWallpaper) {
                        Text(
                            if (hasWallpaper) AppLanguage.tr("更换图片", "Change image")
                            else AppLanguage.tr("选择图片", "Choose image")
                        )
                    }
                    if (hasWallpaper) {
                        TextButton(onClick = onClearWallpaper) { Text(AppLanguage.tr("恢复默认", "Reset to default")) }
                    }
                }
            }
        }
        item { SettingsSection(AppLanguage.tr("容器", "Container")) }
        item {
            SettingsCard(
                title = AppLanguage.tr("顶部渐变提示", "Top gradient indicator"),
                status = if (indicator) {
                    AppLanguage.tr("开启", "On")
                } else {
                    AppLanguage.tr("关闭", "Off")
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        AppLanguage.tr(
                            "在虚拟 App 顶部显示向下渐隐的色带，用于区分容器环境和真实系统；" +
                                "颜色可跟随 App，也可以固定。",
                            "Show a downward-fading band at the top of virtual apps so " +
                                "users can tell the container apart from the real system. " +
                                "The color can follow the app or be fixed."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = indicator,
                        onCheckedChange = {
                            indicator = it
                            ContainerIndicatorSettings.setEnabled(context, it)
                        }
                    )
                }
                if (indicator) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = colorMode == ContainerIndicatorSettings.ColorMode.AUTO,
                            onClick = {
                                colorMode = ContainerIndicatorSettings.ColorMode.AUTO
                                ContainerIndicatorSettings.setAuto(context)
                            },
                            label = {
                                Text(AppLanguage.tr("自动（跟随 App）", "Auto (follow app)"))
                            }
                        )
                        FilterChip(
                            selected = colorMode == ContainerIndicatorSettings.ColorMode.FIXED,
                            onClick = {
                                colorMode = ContainerIndicatorSettings.ColorMode.FIXED
                                ContainerIndicatorSettings.setFixedColor(context, fixedColor)
                            },
                            label = {
                                Text(AppLanguage.tr("固定颜色", "Fixed color"))
                            }
                        )
                    }
                    if (colorMode == ContainerIndicatorSettings.ColorMode.FIXED) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ContainerIndicatorSettings.palette.forEach { color ->
                                val selected = fixedColor == color
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(Color(color))
                                        .border(
                                            width = if (selected) 3.dp else 1.dp,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant
                                            },
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            fixedColor = color
                                            ContainerIndicatorSettings.setFixedColor(
                                                context,
                                                color
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        if (colorMode == ContainerIndicatorSettings.ColorMode.AUTO) {
                            AppLanguage.tr(
                                "自动：App 状态栏有颜色时跟随；透明时使用自适应中性色。",
                                "Auto: follow the app's status bar color; use an adaptive " +
                                    "neutral shade when it is transparent."
                            )
                        } else {
                            AppLanguage.tr(
                                "固定颜色会显示在所有虚拟 App 的顶部。",
                                "The fixed color is shown at the top of every virtual app."
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

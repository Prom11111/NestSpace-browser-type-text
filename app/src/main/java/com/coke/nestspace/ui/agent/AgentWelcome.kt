package com.coke.nestspace.ui.agent

import com.coke.nestspace.i18n.AppLanguage
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coke.nestspace.R

private data class Suggestion(
    val icon: ImageVector,
    val title: String,
    val detail: String,
    val prompt: String
)

private val suggestions = listOf(
    Suggestion(
        Icons.Rounded.Download,
        AppLanguage.tr("下载应用", "Download apps"),
        AppLanguage.tr("搜索并下载网易云音乐 APK", "Search for and download the NetEase Cloud Music APK"),
        AppLanguage.tr(
            "帮我下载网易音乐 app 到系统下载目录",
            "Download the NetEase Cloud Music app to the system Downloads folder"
        )
    ),
    Suggestion(
        Icons.Rounded.Apps,
        AppLanguage.tr("控制容器应用", "Control a container app"),
        AppLanguage.tr("无头启动 testapp，点击按钮并截图", "Launch testapp headlessly, tap the button and screenshot"),
        AppLanguage.tr(
            "以 headless 模式打开 virtual-1 的 Stage B Test，点击按钮并截图",
            "Open Stage B Test in virtual-1 in headless mode, tap the button and screenshot"
        )
    ),
    Suggestion(
        Icons.Rounded.Public,
        AppLanguage.tr("浏览网页", "Browse the web"),
        AppLanguage.tr("用无头浏览器打开并总结网页", "Open and summarize a page in the headless browser"),
        AppLanguage.tr(
            "用无头浏览器打开 https://www.bing.com 并告诉我页面内容",
            "Open https://www.bing.com in the headless browser and tell me what is on the page"
        )
    ),
    Suggestion(
        Icons.Rounded.Info,
        AppLanguage.tr("检查系统", "Check the system"),
        AppLanguage.tr("查看设备、网络和浏览器状态", "Inspect device, network and browser status"),
        AppLanguage.tr(
            "检查当前系统状态，并告诉我浏览器运行模式",
            "Check the current system status and tell me the browser run mode"
        )
    )
)

@Composable
internal fun WelcomeState(
    configured: Boolean,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
    onSuggestion: (String) -> Unit
) {
    if (!configured) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Button(onClick = onConfigure) {
                Text(AppLanguage.tr("去配置", "Configure"))
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 5.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
        ) {
            Image(
                painter = painterResource(R.drawable.ai_assistant),
                contentDescription = null,
                modifier = Modifier
                    .padding(5.dp)
                    .size(88.dp)
                    .clip(CircleShape)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            AppLanguage.tr("欢迎使用 NestSpace Agent", "Welcome to NestSpace Agent"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(7.dp))
        Text(
            AppLanguage.tr(
                "我可以浏览网页、下载文件，也可以控制 virtual-1 里的应用完成多步任务。",
                "I can browse the web, download files and control apps inside " +
                    "virtual-1 to complete multi-step tasks."
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(13.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FeaturePill(AppLanguage.tr("无头浏览器", "Headless browser"))
            FeaturePill(AppLanguage.tr("容器控制", "Container control"))
            FeaturePill(AppLanguage.tr("系统诊断", "System diagnostics"))
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                AppLanguage.tr("试试这样问", "Try asking"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                AppLanguage.tr("点击填入", "Tap to fill"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.height(10.dp))
        suggestions.chunked(2).forEachIndexed { index, rowItems ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEach { suggestion ->
                    SuggestionCard(
                        suggestion = suggestion,
                        modifier = Modifier.weight(1f),
                        onClick = { onSuggestion(suggestion.prompt) }
                    )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun FeaturePill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SuggestionCard(
    suggestion: Suggestion,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .heightIn(min = 112.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    suggestion.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.height(9.dp))
            Text(
                suggestion.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                suggestion.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

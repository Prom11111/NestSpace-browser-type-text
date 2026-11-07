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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
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

private fun suggestions(): List<Suggestion> = listOf(
    Suggestion(
        Icons.Rounded.Search,
        AppLanguage.tr("查最新资讯", "Latest updates"),
        AppLanguage.tr("搜索并整理近期科技新闻", "Search and summarize recent tech news"),
        AppLanguage.tr(
            "帮我搜索最近的科技新闻，并按重要程度整理成几点。",
            "Search for recent technology news and summarize the most important points."
        )
    ),
    Suggestion(
        Icons.Rounded.Public,
        AppLanguage.tr("整理网页", "Summarize a page"),
        AppLanguage.tr("打开网页，提取关键信息", "Open a page and pull out the key points"),
        AppLanguage.tr(
            "打开 https://www.zhihu.com，并总结页面中的主要内容。",
            "Open https://www.zhihu.com and summarize the main content."
        )
    ),
    Suggestion(
        Icons.Rounded.Apps,
        AppLanguage.tr("操作应用", "Use an app"),
        AppLanguage.tr("打开应用并读取当前页面", "Open an app and inspect the current screen"),
        AppLanguage.tr(
            "在 virtual-1 中打开一个已安装应用，读取当前页面并告诉我主要内容。",
            "Open an installed app in virtual-1, inspect the current screen and tell me the main content."
        )
    ),
    Suggestion(
        Icons.Rounded.Info,
        AppLanguage.tr("了解设备状态", "Check device status"),
        AppLanguage.tr("检查网络、存储和运行环境", "Check network, storage and runtime status"),
        AppLanguage.tr(
            "检查当前设备状态，并告诉我需要关注的问题。",
            "Check the current device status and tell me if anything needs attention."
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
            AppLanguage.tr("你好，我是你的 AI 助手", "Hi, I'm your AI assistant"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(7.dp))
        Text(
            AppLanguage.tr(
                "查资料、整理内容、操作应用，或把多步任务交给我。",
                "I can research, organize information, use apps and handle multi-step tasks."
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(13.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FeaturePill(AppLanguage.tr("查资料", "Research"))
            FeaturePill(AppLanguage.tr("操作应用", "Use apps"))
            FeaturePill(AppLanguage.tr("自动化", "Automation"))
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                AppLanguage.tr("你可以试试", "Try one"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                AppLanguage.tr("点一下即可填入", "Tap to fill"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.height(10.dp))
        suggestions().chunked(2).forEachIndexed { index, rowItems ->
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

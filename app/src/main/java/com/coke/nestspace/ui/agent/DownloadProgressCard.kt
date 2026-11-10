package com.coke.nestspace.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.browser.BrowserSession
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.task.OperationTaskRegistry

@Composable
internal fun DownloadProgressCard(
    download: BrowserSession.DownloadState,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    onClick: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null
) {
    val foreground = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val secondary = if (dark) {
        Color.White.copy(alpha = 0.64f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val background = if (dark) {
        Color.White.copy(alpha = 0.09f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    }
    val accent = if (dark) Color(0xFF6CB6FF) else MaterialTheme.colorScheme.primary
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier),
        shape = RoundedCornerShape(16.dp),
        color = background
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Download,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    download.name,
                    color = foreground,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    downloadStatus(download),
                    color = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (download.state == "downloading") {
                    val progress = download.progress
                    if (progress == null) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = accent
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = accent
                        )
                    }
                }
            }
            if (onCancel != null &&
                (download.state == "downloading" || download.state == "cancelling")
            ) {
                TextButton(
                    onClick = onCancel,
                    enabled = download.state == "downloading"
                ) {
                    Text(
                        AppLanguage.tr(
                            if (download.state == "cancelling") "正在取消" else "取消",
                            if (download.state == "cancelling") "Cancelling" else "Cancel"
                        ),
                        color = accent
                    )
                }
            }
        }
    }
}

private fun downloadStatus(download: BrowserSession.DownloadState): String = when {
    download.state == "downloading" -> {
        val percent = download.progress?.let { "${(it * 100).toInt()}% · " }.orEmpty()
        AppLanguage.tr(
            "下载中 · $percent${formatBytes(download.downloadedBytes)}" +
                download.totalBytes.takeIf { it > 0L }?.let {
                    " / ${formatBytes(it)}"
                }.orEmpty(),
            "Downloading · $percent${formatBytes(download.downloadedBytes)}" +
                download.totalBytes.takeIf { it > 0L }?.let {
                    " / ${formatBytes(it)}"
                }.orEmpty()
        )
    }
    download.state == "done" -> AppLanguage.tr(
        "已下载 · ${formatBytes(download.size)}",
        "Downloaded · ${formatBytes(download.size)}"
    )
    download.state == "cancelling" -> AppLanguage.tr(
        "正在停止并清理…",
        "Cancelling and cleaning up…"
    )
    download.state == "cancelled" -> {
        val detail = download.error?.takeUnless {
            it.equals("cancelled", ignoreCase = true) ||
                it.equals("user cancelled", ignoreCase = true) ||
                it.equals("download cancelled", ignoreCase = true)
        }
        AppLanguage.tr(
            "已取消${detail?.let { " · $it" }.orEmpty()}",
            "Cancelled${detail?.let { " · $it" }.orEmpty()}"
        )
    }
    else -> AppLanguage.tr(
        "下载失败${download.error?.let { " · $it" }.orEmpty()}",
        "Download failed${download.error?.let { " · $it" }.orEmpty()}"
    )
}

@Composable
internal fun OperationTaskStatusCard(
    task: OperationTaskRegistry.Snapshot,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    onOpenMirror: (() -> Unit)? = null
) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val title = when {
        task.state == OperationTaskRegistry.State.FAILED &&
            task.message == "interrupted" -> AppLanguage.tr(
            "应用重启，任务已中断",
            "Task interrupted after app restart"
        )
        task.state == OperationTaskRegistry.State.FAILED ->
            AppLanguage.tr("任务失败", "Task failed")
        task.state == OperationTaskRegistry.State.CANCELLED ->
            AppLanguage.tr("任务已取消", "Task cancelled")
        task.state == OperationTaskRegistry.State.SUCCEEDED ->
            AppLanguage.tr("任务已完成", "Task completed")
        task.state == OperationTaskRegistry.State.CANCELLING ->
            AppLanguage.tr("正在取消", "Cancelling")
        task.state == OperationTaskRegistry.State.WAITING_USER ->
            AppLanguage.tr("等待处理", "Waiting for user")
        task.state == OperationTaskRegistry.State.PAUSED ->
            AppLanguage.tr("任务已暂停", "Task paused")
        else -> task.message.ifBlank {
            AppLanguage.tr("任务进行中", "Task running")
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Download,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(
                        title.takeIf(String::isNotBlank),
                        task.error?.takeIf {
                            it.isNotBlank() &&
                                it != "The app process stopped before this task finished."
                        }
                    ).joinToString(" · "),
                    color = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                task.progressPercent?.let {
                    LinearProgressIndicator(
                        progress = { it.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = accent
                    )
                }
            }
            if (onCancel != null && !task.terminal) {
                TextButton(onClick = onCancel) {
                    Text(AppLanguage.tr("取消", "Cancel"), color = accent)
                }
            } else if (onDismiss != null && task.terminal) {
                TextButton(onClick = onDismiss) {
                    Text(
                        AppLanguage.tr("删除", "Delete"),
                        color = secondary
                    )
                }
            }
            if (onOpenMirror != null) {
                TextButton(onClick = onOpenMirror) {
                    Text(AppLanguage.tr("小窗", "Window"), color = accent)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format("%.1f MB", mb)
}

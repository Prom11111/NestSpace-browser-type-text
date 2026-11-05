package com.coke.nestspace.ui.settings

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.agent.TokenUsageEntry
import com.coke.nestspace.agent.TokenUsageSnapshot
import com.coke.nestspace.agent.TokenUsageStore
import com.coke.nestspace.agent.TokenUsageTotals
import com.coke.nestspace.i18n.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/** Per-request token accounting for every model call. */
@Composable
internal fun TokenUsageScreen() {
    AppLanguage.isChinese()
    val context = LocalContext.current
    val store = remember(context) { TokenUsageStore.get(context) }
    var snapshot by remember { mutableStateOf(store.snapshot()) }
    var showReset by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    val todayDate = LocalDate.now()
    val selectedTotals = if (selectedDate == todayDate) {
        snapshot.today
    } else {
        snapshot.days[selectedDate.toString()] ?: TokenUsageTotals()
    }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = withContext(Dispatchers.IO) { store.snapshot() }
            delay(2_000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                AppLanguage.tr(
                    "每次成功请求大模型都会计数，包括重试、最终总结和连通性测试。" +
                        "输入/输出来自接口返回的 usage；接口没有返回时按文本长度估算并标记。",
                    "Every completed model request is counted, including retries, final " +
                        "summaries and connectivity tests. Input/output come from the " +
                        "provider usage object; missing usage is estimated and marked."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            TotalsCard(
                title = if (selectedDate == todayDate) {
                    AppLanguage.tr("今日", "Today")
                } else {
                    dateLabel(selectedDate)
                },
                totals = selectedTotals,
                onToday = if (selectedDate != todayDate) {
                    { selectedDate = todayDate }
                } else {
                    null
                }
            )
        }

        item {
            TotalsCard(
                title = AppLanguage.tr("累计", "All time"),
                totals = snapshot.total
            )
        }

        item {
            TokenCalendarCard(
                days = snapshot.days,
                selected = selectedDate,
                onSelect = { selectedDate = it }
            )
        }

        if (snapshot.models.isNotEmpty()) {
            item {
                Text(
                    AppLanguage.tr("按模型", "By model"),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            itemsIndexed(
                snapshot.models,
                key = { _, model -> "model-${model.id}" }
            ) { index, model ->
                if (index > 0) HorizontalDivider()
                ModelCard(model.name, model.model, model.totals)
            }
        }

        item {
            Text(
                AppLanguage.tr("最近请求", "Recent requests"),
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (snapshot.recent.isEmpty()) {
            item {
                Text(
                    AppLanguage.tr("还没有请求记录", "No requests recorded yet"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            itemsIndexed(
                snapshot.recent.take(20),
                key = { index, entry -> "${entry.at}-$index" }
            ) { _, entry ->
                RecentRequestCard(entry)
            }
        }

        item {
            OutlinedButton(
                onClick = { showReset = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(AppLanguage.tr("清空统计", "Reset counters"))
            }
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(AppLanguage.tr("清空 Token 统计？", "Reset token counters?")) },
            text = {
                Text(
                    AppLanguage.tr(
                        "累计、今日、按模型和最近请求记录都会清空。",
                        "Today, all-time, per-model and recent request records will be cleared."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.reset()
                        snapshot = store.snapshot()
                        showReset = false
                    }
                ) {
                    Text(AppLanguage.tr("清空", "Reset"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }
}

@Composable
private fun TokenCalendarCard(
    days: Map<String, TokenUsageTotals>,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit
) {
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    LaunchedEffect(selected) {
        val target = YearMonth.from(selected)
        if (month != target) month = target
    }
    val monthMax = days
        .filterKeys { it.startsWith(month.toString()) }
        .values
        .maxOfOrNull { it.totalTokens } ?: 0L
    val first = month.atDay(1)
    val leading = (first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val cells = buildList<LocalDate?> {
        repeat(leading) { add(null) }
        for (day in 1..month.lengthOfMonth()) add(month.atDay(day))
        while (size % 7 != 0) add(null)
    }
    val selectedTotals = days[selected.toString()] ?: TokenUsageTotals()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    AppLanguage.tr("Token 日历", "Token calendar"),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = {
                    onSelect(month.minusMonths(1).atDay(1))
                }) {
                    Text("<")
                }
                Text(monthTitle(month), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = {
                    onSelect(month.plusMonths(1).atDay(1))
                }) {
                    Text(">")
                }
            }
            Row(Modifier.fillMaxWidth()) {
                weekLabels().forEach { label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        if (date == null) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            val totals = days[date.toString()] ?: TokenUsageTotals()
                            val ratio = if (monthMax > 0) {
                                totals.totalTokens.toFloat() / monthMax
                            } else {
                                0f
                            }
                            val selectedDay = date == selected
                            var modifier = Modifier
                                .weight(1f)
                                .padding(2.dp)
                                .height(52.dp)
                                .background(
                                    if (totals.totalTokens > 0) {
                                        MaterialTheme.colorScheme.primary.copy(
                                            alpha = 0.10f + 0.38f * ratio
                                        )
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    },
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelect(date) }
                            if (selectedDay) {
                                modifier = modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(8.dp)
                                )
                            }
                            Column(
                                modifier = modifier.padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    date.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (totals.totalTokens > 0) {
                                    Text(
                                        formatCompact(totals.totalTokens),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Text(
                dateLabel(selected),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                AppLanguage.tr(
                    "${formatCount(selectedTotals.requests)} 次请求 · " +
                        "输入 ${formatCount(selectedTotals.inputTokens)} · " +
                        "输出 ${formatCount(selectedTotals.outputTokens)} · " +
                        "思考 ${formatCount(selectedTotals.reasoningTokens)}",
                    "${formatCount(selectedTotals.requests)} requests · " +
                        "input ${formatCount(selectedTotals.inputTokens)} · " +
                        "output ${formatCount(selectedTotals.outputTokens)} · " +
                        "thinking ${formatCount(selectedTotals.reasoningTokens)}"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                AppLanguage.tr(
                    "总计 ${formatCount(selectedTotals.totalTokens)} tokens",
                    "${formatCount(selectedTotals.totalTokens)} tokens total"
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun TotalsCard(
    title: String,
    totals: TokenUsageTotals,
    onToday: (() -> Unit)? = null
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Fixed height: the "back to today" action appears only for a past
            // date and must not change the card's height.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (onToday != null) {
                    Text(
                        AppLanguage.tr("回到今天", "Today"),
                        modifier = Modifier
                            .clickable(onClick = onToday)
                            .padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
                Text(
                    AppLanguage.tr(
                        "${formatCount(totals.requests)} 次请求",
                        "${formatCount(totals.requests)} requests"
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("输入", "Input", totals.inputTokens, Modifier.weight(1f))
                Metric("输出", "Output", totals.outputTokens, Modifier.weight(1f))
                Metric("总计", "Total", totals.totalTokens, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("思考", "Thinking", totals.reasoningTokens, Modifier.weight(1f))
                Metric("缓存输入", "Cached in", totals.cachedInputTokens, Modifier.weight(1f))
                Metric(
                    "估算请求",
                    "Estimated",
                    totals.estimatedRequests,
                    Modifier.weight(1f)
                )
            }
            Text(
                AppLanguage.tr(
                    "思考包含在输出内，只有接口单独返回时才有独立数值；" +
                        "缓存输入包含在输入内。",
                    "Thinking is included in output and only has a separate value when " +
                        "the provider reports it; cached input is included in input."
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModelCard(name: String, model: String, totals: TokenUsageTotals) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                name.ifBlank { model },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (model.isNotBlank() && model != name) {
                Text(
                    model,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                AppLanguage.tr(
                    "${formatCount(totals.requests)} 次请求 · " +
                        "输入 ${formatCount(totals.inputTokens)} · " +
                        "输出 ${formatCount(totals.outputTokens)} · " +
                        "思考 ${formatCount(totals.reasoningTokens)}",
                    "${formatCount(totals.requests)} requests · " +
                        "input ${formatCount(totals.inputTokens)} · " +
                        "output ${formatCount(totals.outputTokens)} · " +
                        "thinking ${formatCount(totals.reasoningTokens)}"
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                AppLanguage.tr(
                    "总计 ${formatCount(totals.totalTokens)} tokens",
                    "${formatCount(totals.totalTokens)} tokens total"
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun RecentRequestCard(entry: TokenUsageEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTime(entry.at),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (entry.estimated) {
                        AppLanguage.tr("估算", "Estimated")
                    } else {
                        kindLabel(entry.kind)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                entry.name.ifBlank { entry.model }.ifBlank { entry.configId },
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                AppLanguage.tr(
                    "输入 ${formatCount(entry.inputTokens)} · " +
                        "输出 ${formatCount(entry.outputTokens)} · " +
                        "思考 ${formatCount(entry.reasoningTokens)} · " +
                        "总计 ${formatCount(entry.totalTokens)}",
                    "input ${formatCount(entry.inputTokens)} · " +
                        "output ${formatCount(entry.outputTokens)} · " +
                        "thinking ${formatCount(entry.reasoningTokens)} · " +
                        "total ${formatCount(entry.totalTokens)}"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Metric(labelZh: String, labelEn: String, value: Long, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            AppLanguage.tr(labelZh, labelEn),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            formatCount(value),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    "chat", "assistant" -> AppLanguage.tr("对话", "Chat")
    "retry" -> AppLanguage.tr("重试", "Retry")
    "summary" -> AppLanguage.tr("总结", "Summary")
    "probe" -> AppLanguage.tr("连通测试", "Probe")
    else -> kind.ifBlank { AppLanguage.tr("请求", "Request") }
}

private fun formatCount(value: Long): String =
    NumberFormat.getIntegerInstance(Locale.US).format(value)

private fun formatCompact(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 10_000 -> String.format(Locale.US, "%.0fk", value / 1_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private fun monthTitle(month: YearMonth): String =
    if (AppLanguage.isChinese()) {
        "${month.year} 年 ${month.monthValue} 月"
    } else {
        "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${month.year}"
    }

private fun weekLabels(): List<String> =
    if (AppLanguage.isChinese()) {
        listOf("一", "二", "三", "四", "五", "六", "日")
    } else {
        listOf("M", "T", "W", "T", "F", "S", "S")
    }

private fun dateLabel(date: LocalDate): String =
    if (AppLanguage.isChinese()) {
        "${date.monthValue} 月 ${date.dayOfMonth} 日"
    } else {
        date.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
    }

private fun formatTime(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(at))

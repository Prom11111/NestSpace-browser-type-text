package com.coke.nestspace.agent

import android.content.Context
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.i18n.AppLanguage
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One stored assistant run, shown in the run-history sheet. */
data class RunSummary(
    val id: String,
    val command: String,
    val startedAt: Long,
    val endedAt: Long,
    val status: String,
    val summary: String,
    val eventCount: Int,
    val modelRequests: Int = 0,
    val toolCalls: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val firstFeedbackMs: Long = 0,
    val hasMetrics: Boolean = false
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0)
    val totalTokens: Long get() = inputTokens + outputTokens
}

/** One line from a run's `events.jsonl`. */
data class RunEvent(
    val at: Long,
    val role: String,
    val text: String
)

/**
 * A single run being recorded. [dir] is null when the filesystem is not
 * available; events are still kept in memory so a stop summary can be built.
 */
class RunRecorder internal constructor(
    private val dir: File?,
    val id: String,
    val command: String,
    val startedAt: Long
) {
    private val lock = Any()
    private val events = mutableListOf<RunEvent>()
    private var finished = false
    private var firstFeedbackAt: Long = 0

    fun record(role: AgentMessage.Role, text: String) {
        val event = RunEvent(
            at = System.currentTimeMillis(),
            role = role.name,
            text = text.take(MAX_EVENT_CHARS)
        )
        synchronized(lock) {
            if (finished) return
            if (firstFeedbackAt == 0L && role != AgentMessage.Role.USER) {
                firstFeedbackAt = event.at
            }
            events += event
            val folder = dir ?: return
            runCatching {
                File(folder, EVENTS_FILE).appendText(
                    JSONObject()
                        .put("at", event.at)
                        .put("role", event.role)
                        .put("text", event.text)
                        .toString() + "\n"
                )
            }
        }
    }

    fun finish(
        status: String,
        summary: String? = null,
        metrics: RunMetricsSnapshot = RunMetricsSnapshot()
    ): RunSummary {
        synchronized(lock) {
            val endedAt = System.currentTimeMillis()
            val firstFeedbackMs = if (firstFeedbackAt > 0L) {
                (firstFeedbackAt - startedAt).coerceAtLeast(0L)
            } else {
                0L
            }
            val result = RunSummary(
                id = id,
                command = command,
                startedAt = startedAt,
                endedAt = endedAt,
                status = status,
                summary = summary.orEmpty(),
                eventCount = events.size,
                modelRequests = metrics.modelRequests,
                toolCalls = metrics.toolCalls,
                inputTokens = metrics.inputTokens,
                outputTokens = metrics.outputTokens,
                reasoningTokens = metrics.reasoningTokens,
                cachedInputTokens = metrics.cachedInputTokens,
                firstFeedbackMs = firstFeedbackMs,
                hasMetrics = true
            )
            if (finished) return result
            finished = true
            val folder = dir ?: return result
            runCatching {
                File(folder, META_FILE).writeText(
                    runMetaJson(
                        id = id,
                        command = command,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        status = status,
                        summary = summary.orEmpty(),
                        eventCount = events.size,
                        metrics = metrics,
                        firstFeedbackMs = firstFeedbackMs
                    )
                )
            }
            return result
        }
    }

    /**
     * Deterministic "what was done / what remains" note. The local model is
     * not called here: a stop must still produce a useful summary when the
     * network or model is unavailable.
     */
    fun stopSummary(chinese: Boolean = AppLanguage.isChinese()): String {
        val snapshot = synchronized(lock) { events.toList() }
        return RunRecordStore.buildStopSummary(command, snapshot, chinese)
    }

    internal fun snapshot(): List<RunEvent> = synchronized(lock) { events.toList() }

    private companion object {
        const val META_FILE = "run.json"
        const val EVENTS_FILE = "events.jsonl"
        const val MAX_EVENT_CHARS = 20_000
    }
}

/**
 * Persists assistant runs under `filesDir/runs/<id>/`:
 *
 * - `run.json`     metadata and the final status/summary
 * - `events.jsonl` one user/tool/agent/error message per line
 *
 * The newest [KEEP_RUNS] runs are kept. Records contain only assistant
 * messages and tool output; they are local to the app and never uploaded.
 */
object RunRecordStore {
    const val STATUS_RUNNING = "running"
    const val STATUS_DONE = "done"
    const val STATUS_STOPPED = "stopped"
    const val STATUS_ERROR = "error"
    const val STATUS_INTERRUPTED = "interrupted"

    private const val RUNS_DIR = "runs"
    private const val META_FILE = "run.json"
    private const val EVENTS_FILE = "events.jsonl"
    private const val KEEP_RUNS = 30
    private const val MAX_EVENT_CHARS = 20_000
    private const val MAX_SUMMARY_CHARS = 1_200

    fun begin(context: Context, command: String): RunRecorder =
        begin(context.filesDir, command)

    fun begin(root: File, command: String): RunRecorder {
        val startedAt = System.currentTimeMillis()
        val id = "run-$startedAt-${UUID.randomUUID().toString().take(8)}"
        val folder = File(File(root, RUNS_DIR), id)
        val ready = runCatching { folder.mkdirs() || folder.isDirectory }.getOrDefault(false)
        val recorder = RunRecorder(
            dir = if (ready) folder else null,
            id = id,
            command = command.trim().take(1_000),
            startedAt = startedAt
        )
        if (ready) {
            runCatching {
                File(folder, META_FILE).writeText(
                    runMetaJson(
                        id = id,
                        command = recorder.command,
                        startedAt = startedAt,
                        endedAt = 0,
                        status = STATUS_RUNNING,
                        summary = "",
                        eventCount = 0
                    )
                )
            }
        }
        prune(root)
        return recorder
    }

    fun recent(context: Context, limit: Int = 20): List<RunSummary> =
        recent(context.filesDir, limit)

    fun recent(root: File, limit: Int = 20): List<RunSummary> {
        val runsDir = File(root, RUNS_DIR)
        return runsDir.listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull { folder ->
                val meta = runCatching {
                    JSONObject(File(folder, META_FILE).readText())
                }.getOrNull() ?: return@mapNotNull null
                summaryFromMeta(meta, folder.name)
            }
            .sortedByDescending { it.startedAt }
            .take(limit.coerceAtLeast(1))
    }

    /**
     * Runs left in [STATUS_RUNNING] belong to a process that is gone: this is
     * called once at main-process startup, before any assistant run can begin.
     * Mark each one interrupted and write a deterministic recovery summary so
     * the history no longer shows a run that can never finish.
     */
    fun recoverInterrupted(
        root: File,
        chinese: Boolean = AppLanguage.isChinese()
    ): List<RunSummary> {
        val runsDir = File(root, RUNS_DIR)
        val recovered = mutableListOf<RunSummary>()
        for (folder in runsDir.listFiles { file -> file.isDirectory }.orEmpty()) {
            val metaFile = File(folder, META_FILE)
            val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull()
                ?: continue
            if (meta.optString("status") != STATUS_RUNNING) continue

            val id = meta.optString("id", folder.name)
            val command = meta.optString("command")
            val startedAt = meta.optLong("started_at")
            val events = events(root, id)
            val lastAt = events.lastOrNull()?.at ?: startedAt
            val endedAt = maxOf(startedAt, minOf(lastAt, System.currentTimeMillis()))
            val summary = buildStopSummary(
                command = command,
                events = events,
                chinese = chinese,
                interrupted = true
            )
            meta.put("status", STATUS_INTERRUPTED)
            meta.put("ended_at", endedAt)
            meta.put("summary", summary)
            meta.put("events", events.size)
            if (runCatching { metaFile.writeText(meta.toString()) }.isSuccess) {
                recovered += summaryFromMeta(meta, folder.name)
            }
        }
        return recovered.sortedByDescending { it.startedAt }
    }

    private fun summaryFromMeta(meta: JSONObject, fallbackId: String): RunSummary =
        RunSummary(
            id = meta.optString("id", fallbackId),
            command = meta.optString("command"),
            startedAt = meta.optLong("started_at"),
            endedAt = meta.optLong("ended_at"),
            status = meta.optString("status", STATUS_RUNNING),
            summary = meta.optString("summary").takeIf { it != "null" }.orEmpty(),
            eventCount = meta.optInt("events"),
            modelRequests = meta.optInt("model_requests"),
            toolCalls = meta.optInt("tool_calls"),
            inputTokens = meta.optLong("input_tokens"),
            outputTokens = meta.optLong("output_tokens"),
            reasoningTokens = meta.optLong("reasoning_tokens"),
            cachedInputTokens = meta.optLong("cached_input_tokens"),
            firstFeedbackMs = meta.optLong("first_feedback_ms"),
            hasMetrics = meta.has("model_requests")
        )

    fun events(context: Context, id: String): List<RunEvent> =
        events(context.filesDir, id)

    fun events(root: File, id: String): List<RunEvent> {
        if (!id.matches(Regex("[A-Za-z0-9._-]+"))) return emptyList()
        val file = File(File(File(root, RUNS_DIR), id), EVENTS_FILE)
        if (!file.isFile) return emptyList()
        return runCatching {
            val tail = ArrayDeque<RunEvent>(1_000)
            file.useLines { lines ->
                lines.forEach { line ->
                    runCatching {
                        val json = JSONObject(line)
                        RunEvent(
                            at = json.optLong("at"),
                            role = json.optString("role"),
                            text = json.optString("text")
                        )
                    }.getOrNull()?.let { event ->
                        tail.addLast(event)
                        if (tail.size > 1_000) tail.removeFirst()
                    }
                }
            }
            tail.toList()
        }.getOrDefault(emptyList())
    }

    fun buildStopSummary(
        command: String,
        events: List<RunEvent>,
        chinese: Boolean = AppLanguage.isChinese(),
        interrupted: Boolean = false
    ): String {
        val toolEvents = events.filter { it.role == AgentMessage.Role.TOOL.name }
        val toolNames = toolEvents.mapNotNull { event ->
            event.text.lineSequence().firstOrNull()
                ?.substringBefore(" -> ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.distinct()
        val lastAgent = events.lastOrNull { it.role == AgentMessage.Role.AGENT.name }
            ?.text?.trim()?.takeIf { it.isNotEmpty() }
        val lastError = events.lastOrNull { it.role == AgentMessage.Role.ERROR.name }
            ?.text?.trim()?.takeIf { it.isNotEmpty() }
        val goal = command.trim().take(200)

        val lines = mutableListOf<String>()
        if (chinese) {
            lines += if (interrupted) "上次任务被中断。" else "已停止这次任务。"
            lines += "目标：$goal"
            if (toolEvents.isEmpty()) {
                lines += "已执行：还没有完成的工具调用。"
            } else {
                val names = toolNames.take(8).joinToString("、")
                lines += "已执行：${toolEvents.size} 次工具调用" +
                    if (names.isBlank()) "。" else "（$names）。"
            }
            lastAgent?.let { lines += "最后的输出：${it.take(300)}" }
            lastError?.let { lines += "遇到的错误：${it.take(200)}" }
            lines += if (interrupted) {
                "这是进程重启后的恢复记录；未完成的部分无法确认，可以从目标重新开始。"
            } else {
                "未完成的部分无法确认。下次说“继续”时，我会带着这份总结接着做。"
            }
        } else {
            lines += if (interrupted) "The previous run was interrupted." else "Stopped this task."
            lines += "Goal: $goal"
            if (toolEvents.isEmpty()) {
                lines += "Done so far: no completed tool calls yet."
            } else {
                val names = toolNames.take(8).joinToString(", ")
                lines += "Done so far: ${toolEvents.size} tool call(s)" +
                    if (names.isBlank()) "." else " ($names)."
            }
            lastAgent?.let { lines += "Last output: ${it.take(300)}" }
            lastError?.let { lines += "Error: ${it.take(200)}" }
            lines += if (interrupted) {
                "This is a recovery record from a process restart; unfinished work could " +
                    "not be confirmed. You can start the goal again."
            } else {
                "Anything unfinished could not be confirmed. Say \"continue\" next time " +
                    "and I will pick up from this summary."
            }
        }
        return lines.joinToString("\n").take(MAX_SUMMARY_CHARS)
    }

    private fun prune(root: File) {
        val runsDir = File(root, RUNS_DIR)
        val dirs = runsDir.listFiles { file -> file.isDirectory }.orEmpty()
        if (dirs.size <= KEEP_RUNS) return
        dirs.sortedByDescending { it.lastModified() }
            .drop(KEEP_RUNS)
            .forEach { runCatching { it.deleteRecursively() } }
    }
}

private fun runMetaJson(
    id: String,
    command: String,
    startedAt: Long,
    endedAt: Long,
    status: String,
    summary: String,
    eventCount: Int,
    metrics: RunMetricsSnapshot = RunMetricsSnapshot(),
    firstFeedbackMs: Long = 0
): String = JSONObject()
    .put("id", id)
    .put("command", command)
    .put("started_at", startedAt)
    .put("ended_at", endedAt)
    .put("status", status)
    .put("summary", summary)
    .put("events", eventCount)
    .put("model_requests", metrics.modelRequests)
    .put("tool_calls", metrics.toolCalls)
    .put("input_tokens", metrics.inputTokens)
    .put("output_tokens", metrics.outputTokens)
    .put("reasoning_tokens", metrics.reasoningTokens)
    .put("cached_input_tokens", metrics.cachedInputTokens)
    .put("first_feedback_ms", firstFeedbackMs)
    .toString()

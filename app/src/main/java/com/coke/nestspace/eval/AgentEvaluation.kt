package com.coke.nestspace.eval

import android.content.Context
import com.coke.nestspace.AppGraph
import com.coke.nestspace.agent.LlmAgentRuntime
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.data.LlmConfigStore
import com.coke.nestspace.engine.EngineResult
import com.coke.nestspace.i18n.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EvalTask(
    val id: String,
    val title: String,
    val goal: String,
    val requiresApp: String?,
    val expectTools: List<String>,
    val expectContains: List<String>,
    val timeoutSeconds: Int
)

enum class EvalStatus { PASS, FAIL, SKIP }

data class EvalResult(
    val task: EvalTask,
    val status: EvalStatus,
    val answer: String,
    val tools: List<String>,
    val durationMs: Long,
    val error: String? = null
)

data class EvaluationSummary(
    val results: List<EvalResult>,
    val startedAt: Long,
    val totalMs: Long,
    val error: String? = null
) {
    fun render(): String {
        val builder = StringBuilder()
        builder.appendLine(AppLanguage.tr("NestSpace Agent 评测", "NestSpace agent evaluation"))
        builder.appendLine(
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(startedAt))
        )
        val passed = results.count { it.status == EvalStatus.PASS }
        val failed = results.count { it.status == EvalStatus.FAIL }
        val skipped = results.count { it.status == EvalStatus.SKIP }
        builder.appendLine(
            AppLanguage.tr(
                "汇总：$passed 通过，$failed 失败，$skipped 跳过，共 ${"%.1f".format(totalMs / 1000.0)} 秒",
                "Summary: $passed passed, $failed failed, $skipped skipped in " +
                    "${"%.1f".format(totalMs / 1000.0)}s"
            )
        )
        error?.let { builder.appendLine("Error: $it") }
        builder.appendLine()
        results.forEach { result ->
            builder.appendLine(
                "${result.task.id}: ${result.status.name.lowercase()} " +
                    "(${result.durationMs}ms) tools=${result.tools.joinToString(",")}"
            )
            if (result.answer.isNotBlank()) {
                builder.appendLine("  ${result.answer.take(300)}")
            }
            result.error?.let { builder.appendLine("  error: $it") }
        }
        return builder.toString().trim()
    }
}

/**
 * Runs a small, deterministic task corpus through the real agent and tool
 * stack. It is a smoke/eval harness, not a benchmark: expected tools and
 * answer fragments are deliberately broad.
 */
class AgentEvaluationRunner(private val context: Context) {

    fun loadTasks(): List<EvalTask> {
        val raw = runCatching {
            context.assets.open(TASKS_FILE).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        return EvalTaskSet.parse(raw)
    }

    suspend fun run(onUpdate: (EvalResult) -> Unit): EvaluationSummary {
        val started = System.currentTimeMillis()
        val tasks = loadTasks()
        val state = LlmConfigStore(context).load()
        val config = state.configs.firstOrNull { it.id == state.activeId }
            ?: state.configs.firstOrNull()
        if (config == null || !config.isConfigured) {
            return EvaluationSummary(
                emptyList(),
                started,
                System.currentTimeMillis() - started,
                AppLanguage.tr(
                    "请先在模型设置里配置模型",
                    "Configure a model in Model settings first"
                )
            )
        }

        val engine = AppGraph.engine(context)
        val installed = when (val result = engine.listInstalled("virtual-1")) {
            is EngineResult.Ok -> result.value.map { it.packageName }.toSet()
            is EngineResult.Error -> emptySet()
        }
        val registry = AppGraph.registry(context)
        val runtime = LlmAgentRuntime(registry)
        val testappWasRunning = ContainerProcessPool.slotFor("com.coke.testapp") != null
        val results = mutableListOf<EvalResult>()

        try {
            for (task in tasks) {
                if (task.requiresApp != null && task.requiresApp !in installed) {
                    val skipped = EvalResult(
                        task,
                        EvalStatus.SKIP,
                        "",
                        emptyList(),
                        0,
                        "app not installed: ${task.requiresApp}"
                    )
                    results += skipped
                    onUpdate(skipped)
                    continue
                }
                val begin = System.currentTimeMillis()
                val result = try {
                    val trace = withTimeout(task.timeoutSeconds * 1_000L) {
                        runtime.run(
                            config = config,
                            command = task.goal,
                            sessionId = "eval-${task.id}"
                        )
                    }
                    evaluate(task, trace, System.currentTimeMillis() - begin)
                } catch (timeout: TimeoutCancellationException) {
                    EvalResult(
                        task,
                        EvalStatus.FAIL,
                        "",
                        emptyList(),
                        System.currentTimeMillis() - begin,
                        "timeout"
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    EvalResult(
                        task,
                        EvalStatus.FAIL,
                        "",
                        emptyList(),
                        System.currentTimeMillis() - begin,
                        t.message ?: t.javaClass.simpleName
                    )
                }
                results += result
                onUpdate(result)
            }
        } finally {
            // Closing a slot kills its process and can block for seconds;
            // never do that on the caller's (UI) thread.
            withContext(Dispatchers.IO) {
                if (!testappWasRunning) {
                    val slot = ContainerProcessPool.slotFor("com.coke.testapp")
                    if (slot != null) {
                        runCatching { ContainerProcessPool.closeSlot(context, slot) }
                    }
                }
            }
        }
        return EvaluationSummary(results, started, System.currentTimeMillis() - started)
    }

    private fun evaluate(task: EvalTask, trace: List<AgentMessage>, durationMs: Long): EvalResult {
        val tools = trace.filter { it.role == AgentMessage.Role.TOOL }
            .mapNotNull { message ->
                message.text.lineSequence().firstOrNull()
                    ?.substringBefore(" -> ")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        val answer = trace.lastOrNull { it.role == AgentMessage.Role.AGENT }?.text.orEmpty()
        // A failed run can still contain a matching tool call and a partial
        // answer. Never report that as PASS.
        val runError = trace.lastOrNull { it.role == AgentMessage.Role.ERROR }?.text
            ?.trim()?.takeIf { it.isNotEmpty() }
        val searchable = (answer + "\n" + trace.joinToString("\n") { it.text }).lowercase()
        val missingTools = task.expectTools.filterNot { it in tools }
        val missingText = task.expectContains.filterNot {
            searchable.contains(it.lowercase())
        }
        val passed = runError == null && missingTools.isEmpty() && missingText.isEmpty() &&
            (task.expectTools.isNotEmpty() || task.expectContains.isNotEmpty())
        val error = when {
            runError != null -> "run error: ${runError.take(300)}"
            !passed && missingTools.isNotEmpty() -> "missing tools: ${missingTools.joinToString()}"
            !passed && missingText.isNotEmpty() -> "missing answer: ${missingText.joinToString()}"
            !passed -> "no expectations matched"
            else -> null
        }
        return EvalResult(
            task,
            if (passed) EvalStatus.PASS else EvalStatus.FAIL,
            answer,
            tools.distinct(),
            durationMs,
            error
        )
    }

    private companion object {
        const val TASKS_FILE = "eval/tasks.json"
    }
}

object EvalTaskSet {
    fun parse(json: String): List<EvalTask> = runCatching {
        val root = JSONObject(json)
        val array = root.optJSONArray("tasks") ?: return emptyList()
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val goal = item.optString("goal").trim()
            if (id.isBlank() || goal.isBlank()) return@mapNotNull null
            EvalTask(
                id = id,
                title = item.optString("title").ifBlank { id },
                goal = goal,
                requiresApp = item.optString("requires_app").takeIf { it.isNotBlank() },
                expectTools = item.optJSONArray("expect_tools")?.let { expected ->
                    (0 until expected.length()).map { expected.optString(it) }
                }.orEmpty(),
                expectContains = item.optJSONArray("expect_contains")?.let { expected ->
                    (0 until expected.length()).map { expected.optString(it) }
                }.orEmpty(),
                timeoutSeconds = item.optInt("timeout_s", 90).coerceIn(10, 300)
            )
        }
    }.getOrDefault(emptyList())
}

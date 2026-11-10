package com.coke.nestspace.agent

import com.coke.nestspace.llm.ChatUsage
import java.util.concurrent.atomic.AtomicReference

/** Per-run counters copied into run.json and shown in run history. */
data class RunMetricsSnapshot(
    val modelRequests: Int = 0,
    val toolCalls: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cachedInputTokens: Long = 0
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/** Thread-safe counters shared by the agent loop and the model usage sink. */
class RunMetrics {

    private val current = AtomicReference(RunMetricsSnapshot())

    fun recordRequest(usage: ChatUsage) {
        current.updateAndGet { snapshot ->
            snapshot.copy(
                modelRequests = snapshot.modelRequests + 1,
                inputTokens = snapshot.inputTokens + usage.inputTokens,
                outputTokens = snapshot.outputTokens + usage.outputTokens,
                reasoningTokens =
                    snapshot.reasoningTokens + usage.reasoningTokens,
                cachedInputTokens =
                    snapshot.cachedInputTokens + usage.cachedInputTokens
            )
        }
    }

    fun recordToolCall() {
        current.updateAndGet { snapshot ->
            snapshot.copy(toolCalls = snapshot.toolCalls + 1)
        }
    }

    fun addToolCalls(count: Int) {
        if (count <= 0) return
        current.updateAndGet { snapshot ->
            snapshot.copy(toolCalls = snapshot.toolCalls + count)
        }
    }

    fun snapshot(): RunMetricsSnapshot = current.get()
}

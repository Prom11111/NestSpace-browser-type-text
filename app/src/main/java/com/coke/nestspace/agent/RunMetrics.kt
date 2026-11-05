package com.coke.nestspace.agent

import com.coke.nestspace.llm.ChatUsage

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

    private val lock = Any()
    private var modelRequests = 0
    private var toolCalls = 0
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var reasoningTokens = 0L
    private var cachedInputTokens = 0L

    fun recordRequest(usage: ChatUsage) {
        synchronized(lock) {
            modelRequests++
            inputTokens += usage.inputTokens
            outputTokens += usage.outputTokens
            reasoningTokens += usage.reasoningTokens
            cachedInputTokens += usage.cachedInputTokens
        }
    }

    fun recordToolCall() {
        synchronized(lock) { toolCalls++ }
    }

    fun addToolCalls(count: Int) {
        if (count <= 0) return
        synchronized(lock) { toolCalls += count }
    }

    fun snapshot(): RunMetricsSnapshot = synchronized(lock) {
        RunMetricsSnapshot(
            modelRequests = modelRequests,
            toolCalls = toolCalls,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
            cachedInputTokens = cachedInputTokens
        )
    }
}

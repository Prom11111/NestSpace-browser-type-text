package com.coke.nestspace.agent

import com.coke.nestspace.llm.ChatUsage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors

class RunMetricsTest {

    @Test
    fun concurrentUpdatesProduceOneConsistentSnapshot() {
        val metrics = RunMetrics()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 8).map {
                executor.submit {
                    repeat(100) {
                        metrics.recordRequest(
                            ChatUsage(
                                inputTokens = 2,
                                outputTokens = 3,
                                reasoningTokens = 1,
                                cachedInputTokens = 1
                            )
                        )
                        metrics.recordToolCall()
                    }
                    metrics.addToolCalls(2)
                }
            }
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val snapshot = metrics.snapshot()
        assertEquals(800, snapshot.modelRequests)
        assertEquals(816, snapshot.toolCalls)
        assertEquals(1_600L, snapshot.inputTokens)
        assertEquals(2_400L, snapshot.outputTokens)
        assertEquals(800L, snapshot.reasoningTokens)
        assertEquals(800L, snapshot.cachedInputTokens)
    }
}

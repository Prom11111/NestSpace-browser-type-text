package com.coke.nestspace.plugin

import com.coke.nestspace.task.OperationTaskRegistry
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTaskHostIntegrationTest {

    @Test
    fun pluginTaskProgressAndSuccessReachUnifiedRegistry() {
        val tasks = AndroidPluginTasks("integration-success")
        try {
            val handle = tasks.submit(
                PluginTaskRequest("plugin-success", 5_000)
            ) { context ->
                context.reportProgress(40, "preparing")
                context.reportProgress(100, "done")
            }
            val result = handle.getResult().get(3, TimeUnit.SECONDS)
            assertTrue(result.isSuccessful)

            val task = OperationTaskRegistry
                .list("plugin:integration-success")
                .first { it.requestId == handle.getId() }
            assertEquals(OperationTaskRegistry.State.SUCCEEDED, task.state)
            assertEquals("done", task.result)
        } finally {
            tasks.closeAll()
        }
    }

    @Test
    fun pluginCancellationReachesUnifiedRegistry() {
        val tasks = AndroidPluginTasks("integration-cancel")
        try {
            val handle = tasks.submit(
                PluginTaskRequest("plugin-cancel", 5_000)
            ) { context ->
                while (!context.isCancelled) {
                    Thread.sleep(10)
                    context.checkCancelled()
                }
            }
            Thread.sleep(30)
            assertTrue(handle.cancel())
            val result = handle.getResult().get(3, TimeUnit.SECONDS)
            assertTrue(result.isCancelled)

            val task = OperationTaskRegistry
                .list("plugin:integration-cancel")
                .first { it.requestId == handle.getId() }
            assertEquals(OperationTaskRegistry.State.CANCELLED, task.state)
        } finally {
            tasks.closeAll()
        }
    }
}

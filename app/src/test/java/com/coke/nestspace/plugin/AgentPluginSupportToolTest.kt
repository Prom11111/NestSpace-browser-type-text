package com.coke.nestspace.plugin

import com.coke.nestspace.tools.Caller
import com.coke.nestspace.tools.ToolContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

class AgentPluginSupportToolTest {

    private val pluginId = "test.agent.tools"

    @After
    fun cleanup() {
        AgentToolPlugins.uninstall(pluginId)
        AgentToolHookPlugins.uninstall(pluginId)
        PluginCommandPlugins.uninstall(pluginId)
    }

    @Test
    fun pluginToolsAndCommandBecomeAgentTools() = runBlocking {
        AgentToolPlugins.install(
            object : AgentToolProvider {
                override fun getDescriptor(): PluginDescriptor =
                    descriptor(PluginCapability.AGENT_TOOLS)

                override fun listToolsAsync():
                    CompletableFuture<List<PluginToolSpec>> =
                    CompletableFuture.completedFuture(
                        listOf(
                            PluginToolSpec(
                                "echo",
                                "Echo",
                                "Echo a value",
                                "{\"type\":\"object\"}",
                                false
                            )
                        )
                    )

                override fun invokeAsync(
                    call: PluginToolCall
                ): CompletableFuture<PluginToolResult> =
                    CompletableFuture.completedFuture(
                        PluginToolResult.success(call.argumentsJson)
                    )
            }
        )
        PluginCommandPlugins.install(
            object : PluginCommandProvider {
                override fun getDescriptor(): PluginDescriptor =
                    descriptor(PluginCapability.COMMANDS)

                override fun listCommandsAsync():
                    CompletableFuture<List<PluginCommandSpec>> =
                    CompletableFuture.completedFuture(
                        listOf(
                            PluginCommandSpec(
                                "refresh",
                                "Refresh",
                                "",
                                "{\"type\":\"object\"}",
                                false,
                                5_000
                            )
                        )
                    )

                override fun invokeCommandAsync(
                    call: PluginCommandCall
                ): CompletableFuture<PluginCommandResult> =
                    CompletableFuture.completedFuture(
                        PluginCommandResult.success("done", "{}")
                    )
            }
        )

        val specs = AgentPluginSupport.toolSpecs()
        assertEquals(2, specs.size)
        val echo = specs.first { it.description.contains("Echo") }
        val result = echo.handler(
            JSONObject().put("value", "hello"),
            ToolContext("session", Caller.LOCAL_AGENT)
        )
        assertTrue(result.ok)
        assertTrue(result.text.contains("hello"))
    }

    @Test
    fun toolHooksCanRewriteArgumentsAndObserveResults() {
        val observed = AtomicReference<String>()
        AgentToolHookPlugins.install(
            object : AgentToolHookPlugin {
                override fun getDescriptor(): PluginDescriptor =
                    descriptor(PluginCapability.AGENT_TOOL_HOOKS)

                override fun beforeToolCallAsync(
                    context: AgentToolCallContext
                ): CompletableFuture<AgentToolCallDecision> =
                    CompletableFuture.completedFuture(
                        AgentToolCallDecision.withArguments(
                            "{\"value\":\"rewritten\"}"
                        )
                    )

                override fun afterToolCallAsync(
                    context: AgentToolResultContext
                ): CompletableFuture<Void> {
                    observed.set(context.resultText)
                    return CompletableFuture.completedFuture(null)
                }
            }
        )
        val toolContext = ToolContext("session", Caller.LOCAL_AGENT)
        val decision = AgentPluginSupport.beforeToolCall(
            "sample",
            JSONObject().put("value", "original"),
            toolContext
        )
        assertEquals("rewritten", decision.arguments.getString("value"))

        AgentPluginSupport.afterToolCall(
            "sample",
            decision.arguments,
            com.coke.nestspace.tools.ToolResult(true, "result"),
            toolContext
        )
        assertEquals("result", observed.get())
    }

    private fun descriptor(capability: PluginCapability): PluginDescriptor =
        PluginDescriptor(
            pluginId,
            "Agent tools",
            "1",
            PluginDescriptor.API_VERSION,
            10,
            EnumSet.of(capability)
        )
}

package com.coke.nestspace.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class PluginRuntimeManagerTest {

    @Test
    fun attachesAndDetachesEveryDeclaredProvider() {
        val plugin = TestPlugin()
        val context = FakeContext(plugin.getDescriptor())

        PluginRuntimeManager.attach(plugin, context)
        assertTrue(plugin.attached.get())
        assertTrue(AgentToolPlugins.snapshot().any {
            it.descriptor.id == plugin.getDescriptor().id
        })
        assertTrue(PluginCommandPlugins.snapshot().any {
            it.descriptor.id == plugin.getDescriptor().id
        })
        assertTrue(PluginLlmProviders.snapshot().any {
            it.providerId == "test-provider"
        })

        PluginRuntimeManager.detach(plugin, context)
        assertTrue(plugin.detached.get())
        assertFalse(AgentToolPlugins.snapshot().any {
            it.descriptor.id == plugin.getDescriptor().id
        })
        assertFalse(PluginCommandPlugins.snapshot().any {
            it.descriptor.id == plugin.getDescriptor().id
        })
        assertFalse(PluginLlmProviders.snapshot().any {
            it.providerId == "test-provider"
        })
    }

    private class TestPlugin :
        PluginLifecycle,
        AgentToolProvider,
        PluginCommandProvider,
        PluginLlmProvider {
        val attached = AtomicBoolean()
        val detached = AtomicBoolean()
        private val pluginDescriptor = PluginDescriptor(
            "test.runtime.manager",
            "Runtime manager",
            "1",
            PluginDescriptor.API_VERSION,
            0,
            EnumSet.of(
                PluginCapability.AGENT_TOOLS,
                PluginCapability.COMMANDS,
                PluginCapability.LLM_PROVIDERS
            )
        )

        override fun getDescriptor(): PluginDescriptor = pluginDescriptor

        override fun onAttach(context: PluginContext) {
            attached.set(true)
        }

        override fun onDetach() {
            detached.set(true)
        }

        override fun listToolsAsync():
            CompletableFuture<List<PluginToolSpec>> =
            CompletableFuture.completedFuture(emptyList())

        override fun invokeAsync(
            call: PluginToolCall
        ): CompletableFuture<PluginToolResult> =
            CompletableFuture.completedFuture(PluginToolResult.success(""))

        override fun listCommandsAsync():
            CompletableFuture<List<PluginCommandSpec>> =
            CompletableFuture.completedFuture(emptyList())

        override fun invokeCommandAsync(
            call: PluginCommandCall
        ): CompletableFuture<PluginCommandResult> =
            CompletableFuture.completedFuture(
                PluginCommandResult.success("", "{}")
            )

        override fun getProviderId(): String = "test-provider"

        override fun getDisplayName(): String = "Test provider"

        override fun getModels(): List<PluginLlmModel> = emptyList()

        override fun executeAsync(
            request: PluginLlmRequest,
            onTextDelta: java.util.function.Consumer<String>
        ): CompletableFuture<PluginLlmResponse> {
            return CompletableFuture.completedFuture(
                PluginLlmResponse("", "[]", "stop", 0, 0)
            )
        }
    }

    private class FakeContext(
        private val pluginDescriptor: PluginDescriptor
    ) : PluginContext {
        private val manifest = PluginManifestParser.parse(
            """
            {
              "manifest_version": 1,
              "id": "${pluginDescriptor.id}",
              "name": "Runtime manager",
              "version": "1",
              "api_version": 2,
              "entry_class": "test.Plugin",
              "permissions": ["agent_tools", "commands", "llm_providers"]
            }
            """.trimIndent()
        )

        override fun getPluginId(): String = pluginDescriptor.id
        override fun getDescriptor(): PluginDescriptor = pluginDescriptor
        override fun getManifest(): PluginManifest = manifest
        override fun getHostVersion(): String = "1"
        override fun getLocale(): Locale = Locale.ENGLISH

        override fun android(): PluginAndroidApi =
            error("not used")

        override fun notifications(): PluginNotificationApi =
            error("not used")

        override fun network(): PluginNetworkApi =
            error("not used")

        override fun storage(): PluginStorageApi =
            error("not used")
    }
}

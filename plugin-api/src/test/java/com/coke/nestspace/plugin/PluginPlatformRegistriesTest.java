package com.coke.nestspace.plugin;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class PluginPlatformRegistriesTest {

    private static final String ID = "test.platform";

    @After
    public void cleanup() {
        AgentToolPlugins.uninstall(ID);
        AgentToolHookPlugins.uninstall(ID);
        PluginCommandPlugins.uninstall(ID);
        PluginUiPlugins.uninstall(ID);
        PluginLlmProviders.uninstallProvider("test-provider");
    }

    @Test
    public void toolRegistryRequiresCapabilityAndCreatesStableNames() {
        AgentToolPlugins.install(toolProvider());
        assertEquals(ID, AgentToolPlugins.snapshot().get(0).getDescriptor().getId());
        assertEquals(
            AgentToolPlugins.qualify(ID, "search.web"),
            AgentToolPlugins.qualify(ID, "search.web")
        );
        assertNotEquals(
            AgentToolPlugins.qualify(ID, "search.web"),
            AgentToolPlugins.qualify(ID, "search_web")
        );

        AgentToolProvider invalid = new AgentToolProvider() {
            @Override
            public PluginDescriptor getDescriptor() {
                return descriptor(
                    "test.invalid",
                    EnumSet.of(PluginCapability.AGENT_QUESTION)
                );
            }

            @Override
            public CompletableFuture<List<PluginToolSpec>> listToolsAsync() {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            @Override
            public CompletableFuture<PluginToolResult> invokeAsync(
                PluginToolCall call
            ) {
                return CompletableFuture.completedFuture(
                    PluginToolResult.success("")
                );
            }
        };
        assertThrows(IllegalArgumentException.class, () ->
            AgentToolPlugins.install(invalid)
        );
    }

    @Test
    public void commandsUiAndLlmsRegister() {
        PluginCommandPlugins.install(commandProvider());
        PluginUiPlugins.install(uiProvider());
        PluginLlmProviders.install(llmProvider());

        assertEquals(1, PluginCommandPlugins.snapshot().size());
        assertEquals(1, PluginUiPlugins.snapshot().size());
        assertEquals(1, PluginLlmProviders.snapshot().size());
    }

    @Test
    public void toolGuardDecisionsAreExplicit() {
        assertEquals(
            AgentToolCallDecision.Action.PROCEED,
            AgentToolCallDecision.proceed().getAction()
        );
        assertEquals(
            "{}",
            AgentToolCallDecision.withArguments("{}").getArgumentsJson()
        );
        assertEquals(
            "blocked",
            AgentToolCallDecision.block("blocked").getErrorMessage()
        );
    }

    private static AgentToolProvider toolProvider() {
        return new AgentToolProvider() {
            @Override
            public PluginDescriptor getDescriptor() {
                return descriptor(
                    ID,
                    EnumSet.of(PluginCapability.AGENT_TOOLS)
                );
            }

            @Override
            public CompletableFuture<List<PluginToolSpec>> listToolsAsync() {
                return CompletableFuture.completedFuture(
                    List.of(
                        new PluginToolSpec(
                            "search.web",
                            "Search",
                            "Search the web",
                            "{\"type\":\"object\"}",
                            false
                        )
                    )
                );
            }

            @Override
            public CompletableFuture<PluginToolResult> invokeAsync(
                PluginToolCall call
            ) {
                return CompletableFuture.completedFuture(
                    PluginToolResult.success("ok")
                );
            }
        };
    }

    private static PluginCommandProvider commandProvider() {
        return new PluginCommandProvider() {
            @Override
            public PluginDescriptor getDescriptor() {
                return descriptor(
                    ID,
                    EnumSet.of(PluginCapability.COMMANDS)
                );
            }

            @Override
            public CompletableFuture<List<PluginCommandSpec>>
                listCommandsAsync() {
                return CompletableFuture.completedFuture(
                    List.of(
                        new PluginCommandSpec(
                            "refresh",
                            "Refresh",
                            "",
                            null
                        )
                    )
                );
            }

            @Override
            public CompletableFuture<PluginCommandResult>
                invokeCommandAsync(PluginCommandCall call) {
                return CompletableFuture.completedFuture(
                    PluginCommandResult.success("ok", "{}")
                );
            }
        };
    }

    private static PluginUiProvider uiProvider() {
        return new PluginUiProvider() {
            @Override
            public PluginDescriptor getDescriptor() {
                return descriptor(
                    ID,
                    EnumSet.of(
                        PluginCapability.UI_PAGES,
                        PluginCapability.UI_RENDERERS
                    )
                );
            }

            @Override
            public CompletableFuture<List<PluginPageSpec>> listPagesAsync() {
                return CompletableFuture.completedFuture(
                    List.of(
                        new PluginPageSpec(
                            "settings",
                            "Settings",
                            "",
                            "pages/settings.json"
                        )
                    )
                );
            }

            @Override
            public CompletableFuture<List<PluginRendererSpec>>
                listRenderersAsync() {
                return CompletableFuture.completedFuture(
                    List.of(
                        new PluginRendererSpec(
                            "chart",
                            "Chart",
                            List.of("chart.v1")
                        )
                    )
                );
            }

            @Override
            public CompletableFuture<String> readPageAsync(String pageId) {
                return CompletableFuture.completedFuture("{}");
            }
        };
    }

    private static PluginLlmProvider llmProvider() {
        return new PluginLlmProvider() {
            @Override
            public PluginDescriptor getDescriptor() {
                return descriptor(
                    ID,
                    EnumSet.of(PluginCapability.LLM_PROVIDERS)
                );
            }

            @Override
            public String getProviderId() {
                return "test-provider";
            }

            @Override
            public String getDisplayName() {
                return "Test provider";
            }

            @Override
            public List<PluginLlmModel> getModels() {
                return List.of(
                    new PluginLlmModel("test-model", "Test", 32_000, false)
                );
            }

            @Override
            public CompletableFuture<PluginLlmResponse> executeAsync(
                PluginLlmRequest request,
                Consumer<String> onTextDelta
            ) {
                return CompletableFuture.completedFuture(
                    new PluginLlmResponse("ok", null, "stop", 1, 1)
                );
            }
        };
    }

    private static PluginDescriptor descriptor(
        String id,
        Set<PluginCapability> capabilities
    ) {
        return new PluginDescriptor(
            id,
            id,
            "1",
            PluginDescriptor.API_VERSION,
            0,
            capabilities
        );
    }
}

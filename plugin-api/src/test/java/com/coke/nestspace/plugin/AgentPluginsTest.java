package com.coke.nestspace.plugin;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentPluginsTest {

    private static final String PLUGIN_ID = "test.agent.hooks";

    @After
    public void cleanup() {
        AgentPlugins.uninstall(PLUGIN_ID);
    }

    @Test
    public void collectsPromptContributionsOnlyFromCapablePlugins() {
        AgentPlugins.install(plugin(true));
        List<AgentPromptContribution> contributions =
            AgentPlugins.promptContributions(context());

        assertEquals(1, contributions.size());
        assertEquals("prompt", contributions.get(0).getId());
        assertEquals("guidance", contributions.get(0).getText());
    }

    @Test
    public void pluginWithoutPromptCapabilityContributesNothing() {
        AgentPlugins.install(plugin(false));
        assertTrue(AgentPlugins.promptContributions(context()).isEmpty());
    }

    @Test
    public void questionResultDistinguishesHandledAndUnhandled() {
        assertFalse(AgentQuestionResult.notHandled().isHandled());
        AgentQuestionResult handled =
            AgentQuestionResult.handled("Use App management");
        assertTrue(handled.isHandled());
        assertEquals("Use App management", handled.getAnswer());
    }

    private static AgentHookPlugin plugin(boolean promptCapability) {
        EnumSet<PluginCapability> capabilities =
            EnumSet.of(PluginCapability.AGENT_QUESTION);
        if (promptCapability) {
            capabilities.add(PluginCapability.AGENT_PROMPT);
        }
        PluginDescriptor descriptor = new PluginDescriptor(
            PLUGIN_ID,
            "Test hooks",
            "1",
            PluginDescriptor.API_VERSION,
            10,
            capabilities
        );
        return new AgentHookPlugin() {
            @Override
            public PluginDescriptor getDescriptor() {
                return descriptor;
            }

            @Override
            public List<AgentPromptContribution> promptContributions(
                AgentPromptContext context
            ) {
                return Collections.singletonList(
                    new AgentPromptContribution(
                        "prompt",
                        "Prompt",
                        "guidance",
                        1
                    )
                );
            }

            @Override
            public CompletableFuture<AgentQuestionResult> answerAsync(
                AgentQuestionContext context
            ) {
                return CompletableFuture.completedFuture(
                    AgentQuestionResult.notHandled()
                );
            }
        };
    }

    private static AgentPromptContext context() {
        return new AgentPromptContext(
            "hello",
            Collections.singletonList("hello"),
            Collections.emptyList(),
            Locale.ENGLISH
        );
    }
}

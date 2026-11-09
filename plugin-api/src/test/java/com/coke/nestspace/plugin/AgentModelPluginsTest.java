package com.coke.nestspace.plugin;

import org.junit.After;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;

public class AgentModelPluginsTest {

    @After
    public void cleanup() {
        AgentModelPlugins.uninstall("test.model.low");
        AgentModelPlugins.uninstall("test.model.high");
    }

    @Test
    public void registryOrdersModelHooksByPriority() {
        AgentModelPlugins.install(plugin("test.model.low", 1));
        AgentModelPlugins.install(plugin("test.model.high", 20));

        List<AgentModelHookPlugin> plugins = AgentModelPlugins.snapshot();
        assertEquals("test.model.high", plugins.get(0).getDescriptor().getId());
        assertEquals("test.model.low", plugins.get(1).getDescriptor().getId());
    }

    @Test
    public void requestDecisionsExposeStableActions() {
        assertEquals(
            AgentModelRequestAction.PROCEED,
            AgentModelRequestDecision.proceed().getAction()
        );
        assertEquals(
            "override",
            AgentModelRequestDecision.withSystemPrompt("override")
                .getSystemPrompt()
        );
        assertEquals(
            "answer",
            AgentModelRequestDecision.shortCircuit("answer").getAnswer()
        );
        assertEquals(
            "blocked",
            AgentModelRequestDecision.block("blocked").getErrorMessage()
        );
    }

    private static AgentModelHookPlugin plugin(String id, int priority) {
        PluginDescriptor descriptor = new PluginDescriptor(
            id,
            id,
            "1",
            PluginDescriptor.API_VERSION,
            priority,
            EnumSet.of(PluginCapability.AGENT_MODEL_REQUEST)
        );
        return new AgentModelHookPlugin() {
            @Override
            public PluginDescriptor getDescriptor() {
                return descriptor;
            }

            @Override
            public CompletableFuture<AgentModelRequestDecision>
                beforeRequestAsync(AgentModelRequestContext context) {
                return CompletableFuture.completedFuture(
                    AgentModelRequestDecision.proceed()
                );
            }
        };
    }
}

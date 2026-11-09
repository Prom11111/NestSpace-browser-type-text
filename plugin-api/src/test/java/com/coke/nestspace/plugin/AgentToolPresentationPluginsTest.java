package com.coke.nestspace.plugin;

import org.junit.After;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentToolPresentationPluginsTest {

    @After
    public void cleanup() {
        AgentToolPresentationPlugins.uninstall("test.presentation.low");
        AgentToolPresentationPlugins.uninstall("test.presentation.high");
    }

    @Test
    public void registryOrdersPresentationPluginsByPriority() {
        AgentToolPresentationPlugins.install(plugin("test.presentation.low", 1));
        AgentToolPresentationPlugins.install(plugin("test.presentation.high", 20));

        List<AgentToolPresentationPlugin> plugins =
            AgentToolPresentationPlugins.snapshot();
        assertEquals(
            "test.presentation.high",
            plugins.get(0).getDescriptor().getId()
        );
    }

    @Test
    public void presentationValuesAreLengthLimited() {
        AgentToolPresentation presentation = new AgentToolPresentation(
            repeat("t", 200),
            repeat("s", 600),
            repeat("d", 20_100),
            "Done",
            "renderer"
        );

        assertEquals(120, presentation.getTitle().length());
        assertEquals(500, presentation.getSummary().length());
        assertEquals(20_000, presentation.getDetail().length());
        assertTrue(presentation.getStatusLabel().contains("Done"));
    }

    @Test
    public void customAndroidViewFactoryIsPreserved() {
        AgentToolViewFactory factory = context -> null;
        AgentToolPresentation presentation = new AgentToolPresentation(
            "Custom",
            "Summary",
            "{}",
            "Done",
            "nestspace.ui.v1",
            factory
        );
        assertTrue(presentation.getViewFactory() == factory);
    }

    private static AgentToolPresentationPlugin plugin(
        String id,
        int priority
    ) {
        PluginDescriptor descriptor = new PluginDescriptor(
            id,
            id,
            "1",
            PluginDescriptor.API_VERSION,
            priority,
            EnumSet.of(PluginCapability.AGENT_TOOL_PRESENTATION)
        );
        return new AgentToolPresentationPlugin() {
            @Override
            public PluginDescriptor getDescriptor() {
                return descriptor;
            }

            @Override
            public CompletableFuture<AgentToolPresentation> presentAsync(
                AgentToolPresentationContext context
            ) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}

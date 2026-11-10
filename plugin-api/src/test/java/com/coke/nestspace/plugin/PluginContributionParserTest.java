package com.coke.nestspace.plugin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PluginContributionParserTest {

    @Test
    public void parsesToolAndCommandDocuments() {
        PluginToolSpec tool = PluginContributionParser.parseTools(
            "{\"items\":[{"
                + "\"id\":\"search\","
                + "\"title\":\"Search\","
                + "\"description\":\"Search remote data\","
                + "\"input_schema\":{\"type\":\"object\"},"
                + "\"mutating\":false"
                + "}]}"
        ).get(0);
        assertEquals("search", tool.getId());
        assertTrue(!tool.isMutating());
        assertEquals(
            PluginToolSpec.DEFAULT_MAX_WAIT_MS,
            tool.getMaxWaitMs()
        );

        PluginCommandSpec command = PluginContributionParser.parseCommands(
            "{\"items\":[{"
                + "\"id\":\"refresh\","
                + "\"title\":\"Refresh\","
                + "\"mutating\":false"
                + "}]}"
        ).get(0);
        assertEquals("refresh", command.getId());
        assertTrue(!command.isMutating());
    }

    @Test
    public void parsesPerToolMaximumSynchronousWait() {
        PluginToolSpec tool = PluginContributionParser.parseTools(
            "{\"items\":[{"
                + "\"id\":\"index\","
                + "\"title\":\"Index\","
                + "\"input_schema\":{\"type\":\"object\"},"
                + "\"timeout_ms\":45000,"
                + "\"max_wait_ms\":5000"
                + "}]}"
        ).get(0);
        PluginCommandSpec command = PluginContributionParser.parseCommands(
            "{\"items\":[{"
                + "\"id\":\"sync\","
                + "\"title\":\"Sync\","
                + "\"timeout_ms\":20000,"
                + "\"max_wait_ms\":300"
                + "}]}"
        ).get(0);

        assertEquals(45_000, tool.getTimeoutMs());
        assertEquals(5_000, tool.getMaxWaitMs());
        assertEquals(20_000, command.getTimeoutMs());
        assertEquals(300, command.getMaxWaitMs());
    }

    @Test
    public void rejectsMissingItems() {
        assertThrows(IllegalArgumentException.class, () ->
            PluginContributionParser.parseTools("{}")
        );
    }
}

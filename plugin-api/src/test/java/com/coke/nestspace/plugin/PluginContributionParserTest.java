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
    public void rejectsMissingItems() {
        assertThrows(IllegalArgumentException.class, () ->
            PluginContributionParser.parseTools("{}")
        );
    }
}

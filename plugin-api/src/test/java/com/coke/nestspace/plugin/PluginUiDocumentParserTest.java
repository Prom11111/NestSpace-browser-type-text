package com.coke.nestspace.plugin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PluginUiDocumentParserTest {

    @Test
    public void parsesDeclarativePageBlocks() {
        PluginUiDocument document = PluginUiDocumentParser.parse(
            "{\"title\":\"Dashboard\",\"blocks\":["
                + "{\"type\":\"text\",\"text\":\"Hello\"},"
                + "{\"type\":\"button\",\"label\":\"Refresh\","
                + "\"action\":\"example/refresh\"},"
                + "{\"type\":\"divider\"}"
                + "]}"
        );
        assertEquals("Dashboard", document.getTitle());
        assertEquals(3, document.getBlocks().size());
        assertEquals("button", document.getBlocks().get(1).getType());
    }

    @Test
    public void rejectsUnknownBlocks() {
        assertThrows(IllegalArgumentException.class, () ->
            PluginUiDocumentParser.parse(
                "{\"blocks\":[{\"type\":\"webview\"}]}"
            )
        );
    }
}

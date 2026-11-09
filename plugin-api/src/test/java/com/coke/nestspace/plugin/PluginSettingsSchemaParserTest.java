package com.coke.nestspace.plugin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PluginSettingsSchemaParserTest {

    @Test
    public void parsesTypedSettings() {
        PluginSettingsSchema schema = PluginSettingsSchemaParser.parse(
            "{\"title\":\"Settings\",\"fields\":["
                + "{\"id\":\"api_key\",\"title\":\"API key\","
                + "\"type\":\"secret\",\"required\":true},"
                + "{\"id\":\"enabled\",\"title\":\"Enabled\","
                + "\"type\":\"boolean\",\"default\":\"true\"},"
                + "{\"id\":\"mode\",\"title\":\"Mode\","
                + "\"type\":\"select\",\"options\":[\"a\",\"b\"]}"
                + "]}"
        );

        assertEquals("Settings", schema.getTitle());
        assertEquals(3, schema.getFields().size());
        assertEquals(
            PluginSettingsField.Type.SECRET,
            schema.getFields().get(0).getType()
        );
        assertTrue(schema.getFields().get(0).isRequired());
    }

    @Test
    public void rejectsDuplicateOrInvalidFields() {
        assertThrows(IllegalArgumentException.class, () ->
            PluginSettingsSchemaParser.parse(
                "{\"fields\":["
                    + "{\"id\":\"x\",\"title\":\"X\",\"type\":\"text\"},"
                    + "{\"id\":\"x\",\"title\":\"X2\",\"type\":\"text\"}"
                    + "]}"
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            PluginSettingsSchemaParser.parse(
                "{\"fields\":["
                    + "{\"id\":\"x\",\"title\":\"X\",\"type\":\"select\"}"
                    + "]}"
            )
        );
    }
}

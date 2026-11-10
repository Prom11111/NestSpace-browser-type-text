package com.coke.nestspace.plugin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PluginSettingViewTest {

    @Test
    public void parsesTypedSettings() {
        PluginSettingView view = PluginSettingView.importSchema(
            "{\"title\":\"Settings\",\"description\":\"Configure the plugin\"," +
                "\"fields\":["
                + "{\"id\":\"api_key\",\"title\":\"API key\","
                + "\"type\":\"secret\",\"required\":true},"
                + "{\"id\":\"enabled\",\"title\":\"Enabled\","
                + "\"type\":\"boolean\",\"default\":\"true\"},"
                + "{\"id\":\"mode\",\"title\":\"Mode\","
                + "\"type\":\"select\",\"options\":[\"a\",\"b\"]},"
                + "{\"id\":\"endpoint\",\"title\":\"Endpoint\","
                + "\"type\":\"text\",\"format\":\"url\",\"required\":true,"
                + "\"max_length\":2048}"
                + "]}"
        );

        assertEquals("Settings", view.getTitle());
        assertEquals("Configure the plugin", view.getDescription());
        assertEquals(4, view.getFields().size());
        assertEquals(
            PluginSettingsField.Type.SECRET,
            view.getFields().get(0).getType()
        );
        assertTrue(view.getFields().get(0).isRequired());
        assertEquals(
            "",
            view.getFields().get(3).validate("https://example.com/tts")
        );
        assertEquals(
            "Endpoint must be an HTTP(S) URL",
            view.getFields().get(3).validate("not a url")
        );
        assertEquals(
            2048,
            view.getFields().get(3).getMaxLength()
        );
    }

    @Test
    public void rejectsDuplicateOrInvalidFields() {
        assertThrows(IllegalArgumentException.class, () ->
            PluginSettingView.importSchema(
                "{\"fields\":["
                    + "{\"id\":\"x\",\"title\":\"X\",\"type\":\"text\"},"
                    + "{\"id\":\"x\",\"title\":\"X2\",\"type\":\"text\"}"
                    + "]}"
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            PluginSettingView.importSchema(
                "{\"fields\":["
                    + "{\"id\":\"x\",\"title\":\"X\",\"type\":\"select\"}"
                    + "]}"
            )
        );
    }

    @Test
    public void validatesRequiredLengthRangeAndPattern() {
        PluginSettingView view = PluginSettingView.importSchema(
            "{\"fields\":["
                + "{\"id\":\"name\",\"title\":\"Name\",\"type\":\"text\","
                + "\"required\":true,\"min_length\":3,\"max_length\":8},"
                + "{\"id\":\"port\",\"title\":\"Port\",\"type\":\"integer\","
                + "\"min\":1,\"max\":65535},"
                + "{\"id\":\"slug\",\"title\":\"Slug\",\"type\":\"text\","
                + "\"pattern\":\"^[a-z0-9-]+$\","
                + "\"pattern_message\":\"Use lowercase slug characters\"}"
                + "]}"
        );

        assertEquals(
            "Name is required",
            view.getFields().get(0).validate(" ")
        );
        assertEquals(
            "Name must contain at least 3 characters",
            view.getFields().get(0).validate("ab")
        );
        assertEquals(
            "Port must be at most 65535",
            view.getFields().get(1).validate("70000")
        );
        assertEquals(
            "Use lowercase slug characters",
            view.getFields().get(2).validate("Invalid Slug")
        );
    }

    @Test
    public void rejectsInvalidDefaultValue() {
        assertThrows(IllegalArgumentException.class, () ->
            PluginSettingView.importSchema(
                "{\"fields\":["
                    + "{\"id\":\"mode\",\"title\":\"Mode\","
                    + "\"type\":\"select\",\"options\":[\"a\",\"b\"],"
                    + "\"default\":\"missing\"}"
                    + "]}"
            )
        );
    }
}

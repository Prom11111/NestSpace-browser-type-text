package com.coke.nestspace.plugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PluginSettingsSchemaParser {

    private PluginSettingsSchemaParser() {
    }

    public static PluginSettingsSchema parse(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray array = root.optJSONArray("fields");
            if (array == null || array.length() == 0) {
                return new PluginSettingsSchema(
                    root.optString("title", "Plugin settings"),
                    List.of()
                );
            }
            if (array.length() > 128) {
                throw new IllegalArgumentException(
                    "settings schema contains too many fields"
                );
            }
            List<PluginSettingsField> fields = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    throw new IllegalArgumentException(
                        "settings fields must be objects"
                    );
                }
                String typeValue = item.optString("type", "text")
                    .toUpperCase(Locale.ROOT);
                PluginSettingsField.Type type;
                try {
                    type = PluginSettingsField.Type.valueOf(typeValue);
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException(
                        "unsupported settings field type: " + typeValue
                    );
                }
                List<String> options = new ArrayList<>();
                JSONArray optionArray = item.optJSONArray("options");
                if (optionArray != null) {
                    for (int option = 0;
                         option < optionArray.length();
                         option++) {
                        options.add(optionArray.optString(option));
                    }
                }
                PluginSettingsField field = new PluginSettingsField(
                    item.optString("id"),
                    item.optString("title"),
                    item.optString("description"),
                    type,
                    item.optString("default"),
                    item.optBoolean("required", false),
                    options
                );
                if (!ids.add(field.getId())) {
                    throw new IllegalArgumentException(
                        "duplicate settings field: " + field.getId()
                    );
                }
                fields.add(field);
            }
            return new PluginSettingsSchema(
                root.optString("title", "Plugin settings"),
                fields
            );
        } catch (JSONException error) {
            throw new IllegalArgumentException(
                "invalid plugin settings JSON: " + error.getMessage(),
                error
            );
        }
    }
}

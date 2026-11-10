package com.coke.nestspace.plugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Host-rendered settings form contributed by a plugin.
 */
public final class PluginSettingView {

    private final String title;
    private final String description;
    private final List<PluginSettingsField> fields;

    public PluginSettingView(
        String title,
        String description,
        List<PluginSettingsField> fields
    ) {
        this.title = title == null ? "" : title.trim();
        this.description = description == null ? "" : description.trim();
        List<PluginSettingsField> values = fields == null
            ? Collections.emptyList()
            : new ArrayList<>(fields);
        if (values.size() > 128) {
            throw new IllegalArgumentException(
                "setting view contains too many fields"
            );
        }
        Set<String> ids = new HashSet<>();
        for (PluginSettingsField field : values) {
            if (!ids.add(field.getId())) {
                throw new IllegalArgumentException(
                    "duplicate settings field: " + field.getId()
                );
            }
        }
        this.fields = Collections.unmodifiableList(values);
    }

    /**
     * Imports the JSON schema stored in {@code contributes.settings}.
     */
    public static PluginSettingView importSchema(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray array = root.optJSONArray("fields");
            if (array == null || array.length() == 0) {
                return new PluginSettingView(
                    root.optString("title", "Plugin settings"),
                    root.optString("description", ""),
                    Collections.emptyList()
                );
            }
            if (array.length() > 128) {
                throw new IllegalArgumentException(
                    "setting view contains too many fields"
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
                String formatValue = item.optString("format", "none")
                    .toUpperCase(Locale.ROOT);
                PluginSettingsField.Format format;
                try {
                    format = PluginSettingsField.Format.valueOf(formatValue);
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException(
                        "unsupported settings field format: " + formatValue
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
                PluginSettingsField.Builder builder =
                    new PluginSettingsField.Builder(
                        item.optString("id"),
                        item.optString("title"),
                        type
                    )
                        .description(item.optString("description"))
                        .placeholder(item.optString("placeholder"))
                        .format(format)
                        .defaultValue(item.optString("default"))
                        .required(item.optBoolean("required", false))
                        .options(options)
                        .pattern(
                            item.optString("pattern"),
                            item.optString(
                                "pattern_message",
                                item.optString("patternMessage")
                            )
                        )
                        .length(
                            item.optInt(
                                "min_length",
                                item.optInt("minLength", 0)
                            ),
                            item.optInt(
                                "max_length",
                                item.optInt(
                                    "maxLength",
                                    PluginSettingsApi.MAX_STRING_CHARS
                                )
                            )
                        );
                if (item.has("min") || item.has("max")) {
                    builder.range(
                        item.has("min") ? item.optLong("min") : null,
                        item.has("max") ? item.optLong("max") : null
                    );
                }
                PluginSettingsField field = builder.build();
                if (!ids.add(field.getId())) {
                    throw new IllegalArgumentException(
                        "duplicate settings field: " + field.getId()
                    );
                }
                fields.add(field);
            }
            return new PluginSettingView(
                root.optString("title", "Plugin settings"),
                root.optString("description", ""),
                fields
            );
        } catch (JSONException error) {
            throw new IllegalArgumentException(
                "invalid plugin settings JSON: " + error.getMessage(),
                error
            );
        }
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public List<PluginSettingsField> getFields() {
        return fields;
    }
}

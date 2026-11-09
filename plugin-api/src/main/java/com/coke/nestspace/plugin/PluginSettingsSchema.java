package com.coke.nestspace.plugin;

import java.util.Collections;
import java.util.List;

public final class PluginSettingsSchema {

    private final String title;
    private final List<PluginSettingsField> fields;

    PluginSettingsSchema(String title, List<PluginSettingsField> fields) {
        this.title = title == null ? "" : title;
        this.fields = fields == null || fields.isEmpty()
            ? Collections.emptyList()
            : Collections.unmodifiableList(fields);
    }

    public String getTitle() {
        return title;
    }

    public List<PluginSettingsField> getFields() {
        return fields;
    }
}

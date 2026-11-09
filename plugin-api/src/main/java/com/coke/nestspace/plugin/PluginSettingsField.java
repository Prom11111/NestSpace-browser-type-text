package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PluginSettingsField {

    public enum Type {
        TEXT,
        BOOLEAN,
        INTEGER,
        SELECT,
        SECRET
    }

    private final String id;
    private final String title;
    private final String description;
    private final Type type;
    private final String defaultValue;
    private final boolean required;
    private final List<String> options;

    public PluginSettingsField(
        String id,
        String title,
        String description,
        Type type,
        String defaultValue,
        boolean required,
        List<String> options
    ) {
        this.id = Objects.requireNonNull(id, "id").trim();
        this.title = title == null ? "" : title.trim();
        this.description = description == null ? "" : description.trim();
        this.type = Objects.requireNonNull(type, "type");
        this.defaultValue = defaultValue == null ? "" : defaultValue;
        this.required = required;
        this.options = options == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(options));
        if (!this.id.matches("[A-Za-z0-9_.-]{1,128}")) {
            throw new IllegalArgumentException("invalid settings field id");
        }
        if (this.title.isEmpty()) {
            throw new IllegalArgumentException(
                "settings field title is required"
            );
        }
        if (type == Type.SELECT && this.options.isEmpty()) {
            throw new IllegalArgumentException(
                "select settings field requires options"
            );
        }
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Type getType() {
        return type;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public boolean isRequired() {
        return required;
    }

    public List<String> getOptions() {
        return options;
    }
}

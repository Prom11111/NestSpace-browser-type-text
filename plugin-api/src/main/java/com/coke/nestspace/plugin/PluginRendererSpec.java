package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PluginRendererSpec {

    private final String id;
    private final String title;
    private final List<String> supportedTypes;

    public PluginRendererSpec(
        String id,
        String title,
        List<String> supportedTypes
    ) {
        this.id = Objects.requireNonNull(id, "id").trim();
        this.title = title == null ? "" : title.trim();
        List<String> values = new ArrayList<>();
        if (supportedTypes != null) {
            for (String value : supportedTypes) {
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
        }
        this.supportedTypes = Collections.unmodifiableList(values);
        if (!this.id.matches("[A-Za-z][A-Za-z0-9_.-]{0,95}")) {
            throw new IllegalArgumentException("invalid renderer id");
        }
        if (this.title.isEmpty()) {
            throw new IllegalArgumentException("renderer title is required");
        }
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public List<String> getSupportedTypes() {
        return supportedTypes;
    }
}

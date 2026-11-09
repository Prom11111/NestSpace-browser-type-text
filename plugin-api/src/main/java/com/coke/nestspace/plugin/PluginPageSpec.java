package com.coke.nestspace.plugin;

import java.util.Objects;

public final class PluginPageSpec {

    private final String id;
    private final String title;
    private final String description;
    private final String entryPath;

    public PluginPageSpec(
        String id,
        String title,
        String description,
        String entryPath
    ) {
        this.id = Objects.requireNonNull(id, "id").trim();
        this.title = title == null ? "" : title.trim();
        this.description = description == null ? "" : description.trim();
        this.entryPath = Objects.requireNonNull(entryPath, "entryPath").trim();
        if (!this.id.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid page id");
        }
        if (this.title.isEmpty()) {
            throw new IllegalArgumentException("page title is required");
        }
        PluginManifestPaths.requireRelative(this.entryPath, "entryPath");
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

    public String getEntryPath() {
        return entryPath;
    }
}

package com.coke.nestspace.plugin;

import java.util.Objects;

public final class PluginNotificationAction {

    private final String id;
    private final String title;

    public PluginNotificationAction(String id, String title) {
        this.id = Objects.requireNonNull(id, "id").trim();
        this.title = title == null ? "" : title.trim();
        if (!this.id.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid notification action id");
        }
        if (this.title.isEmpty() || this.title.length() > 60) {
            throw new IllegalArgumentException(
                "notification action title is required and limited to 60 chars"
            );
        }
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }
}

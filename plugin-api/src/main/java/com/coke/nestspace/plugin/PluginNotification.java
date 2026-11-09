package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PluginNotification {

    private final String id;
    private final String title;
    private final String body;
    private final List<PluginNotificationAction> actions;

    public PluginNotification(String id, String title, String body) {
        this(id, title, body, Collections.emptyList());
    }

    public PluginNotification(
        String id,
        String title,
        String body,
        List<PluginNotificationAction> actions
    ) {
        this.id = limit(Objects.requireNonNull(id, "id"), 100);
        this.title = limit(Objects.requireNonNull(title, "title"), 120);
        this.body = limit(body == null ? "" : body, 1_000);
        List<PluginNotificationAction> values = new ArrayList<>();
        if (actions != null) values.addAll(actions);
        if (values.size() > 3) {
            throw new IllegalArgumentException(
                "a plugin notification supports at most 3 actions"
            );
        }
        Set<String> actionIds = new HashSet<>();
        for (PluginNotificationAction action : values) {
            if (!actionIds.add(action.getId())) {
                throw new IllegalArgumentException(
                    "duplicate notification action: " + action.getId()
                );
            }
        }
        this.actions = Collections.unmodifiableList(values);
        if (this.id.isEmpty() || this.title.isEmpty()) {
            throw new IllegalArgumentException("notification id and title are required");
        }
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public List<PluginNotificationAction> getActions() {
        return actions;
    }

    private static String limit(String value, int maxChars) {
        String trimmed = value.trim();
        return trimmed.length() <= maxChars
            ? trimmed
            : trimmed.substring(0, maxChars);
    }
}

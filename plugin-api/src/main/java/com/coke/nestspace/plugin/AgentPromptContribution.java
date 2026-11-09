package com.coke.nestspace.plugin;

import java.util.Objects;

public final class AgentPromptContribution {

    private final String id;
    private final String title;
    private final String text;
    private final int priority;

    public AgentPromptContribution(
        String id,
        String title,
        String text,
        int priority
    ) {
        this.id = Objects.requireNonNull(id, "id").trim();
        this.title = Objects.requireNonNull(title, "title").trim();
        this.text = Objects.requireNonNull(text, "text").trim();
        this.priority = priority;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getText() {
        return text;
    }

    public int getPriority() {
        return priority;
    }
}

package com.coke.nestspace.plugin;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class AgentPromptContext {

    private final String userMessage;
    private final List<String> recentUserMessages;
    private final List<String> availableToolNames;
    private final Locale locale;

    public AgentPromptContext(
        String userMessage,
        List<String> recentUserMessages,
        List<String> availableToolNames,
        Locale locale
    ) {
        this.userMessage = Objects.requireNonNull(userMessage, "userMessage");
        this.recentUserMessages = recentUserMessages == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(recentUserMessages);
        this.availableToolNames = availableToolNames == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(availableToolNames);
        this.locale = Objects.requireNonNull(locale, "locale");
    }

    public String getUserMessage() {
        return userMessage;
    }

    public List<String> getRecentUserMessages() {
        return recentUserMessages;
    }

    public List<String> getAvailableToolNames() {
        return availableToolNames;
    }

    public Locale getLocale() {
        return locale;
    }
}

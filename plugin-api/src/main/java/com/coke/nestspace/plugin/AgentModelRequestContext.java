package com.coke.nestspace.plugin;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class AgentModelRequestContext {

    private final String requestId;
    private final String protocol;
    private final String model;
    private final String userMessage;
    private final String systemPrompt;
    private final List<String> toolNames;
    private final int round;

    public AgentModelRequestContext(
        String requestId,
        String protocol,
        String model,
        String userMessage,
        String systemPrompt,
        List<String> toolNames,
        int round
    ) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.model = Objects.requireNonNull(model, "model");
        this.userMessage = Objects.requireNonNull(userMessage, "userMessage");
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        this.toolNames = toolNames == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(toolNames);
        this.round = round;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getModel() {
        return model;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public List<String> getToolNames() {
        return toolNames;
    }

    public int getRound() {
        return round;
    }
}

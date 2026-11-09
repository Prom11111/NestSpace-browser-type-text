package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * A validated plugin tool invocation from the Agent.
 */
public final class PluginToolCall {

    private final String toolId;
    private final String argumentsJson;
    private final String sessionId;
    private final String spaceId;

    public PluginToolCall(
        String toolId,
        String argumentsJson,
        String sessionId,
        String spaceId
    ) {
        this.toolId = Objects.requireNonNull(toolId, "toolId");
        this.argumentsJson = Objects.requireNonNull(
            argumentsJson,
            "argumentsJson"
        );
        this.sessionId = sessionId == null ? "" : sessionId;
        this.spaceId = spaceId == null ? "" : spaceId;
    }

    public String getToolId() {
        return toolId;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getSpaceId() {
        return spaceId;
    }
}

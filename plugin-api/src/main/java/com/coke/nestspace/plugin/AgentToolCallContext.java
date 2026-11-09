package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * Tool invocation visible to a before-tool Hook.
 */
public final class AgentToolCallContext {

    private final String toolName;
    private final String argumentsJson;
    private final String sessionId;
    private final String spaceId;

    public AgentToolCallContext(
        String toolName,
        String argumentsJson,
        String sessionId,
        String spaceId
    ) {
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.argumentsJson = Objects.requireNonNull(
            argumentsJson,
            "argumentsJson"
        );
        this.sessionId = sessionId == null ? "" : sessionId;
        this.spaceId = spaceId == null ? "" : spaceId;
    }

    public String getToolName() {
        return toolName;
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

package com.coke.nestspace.plugin;

import java.util.Objects;

public final class PluginCommandCall {

    private final String commandId;
    private final String argumentsJson;
    private final String sessionId;
    private final String spaceId;

    public PluginCommandCall(String commandId, String argumentsJson) {
        this(commandId, argumentsJson, "", "");
    }

    public PluginCommandCall(
        String commandId,
        String argumentsJson,
        String sessionId,
        String spaceId
    ) {
        this.commandId = Objects.requireNonNull(commandId, "commandId");
        this.argumentsJson = Objects.requireNonNull(
            argumentsJson,
            "argumentsJson"
        );
        this.sessionId = sessionId == null ? "" : sessionId;
        this.spaceId = spaceId == null ? "" : spaceId;
    }

    public String getCommandId() {
        return commandId;
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

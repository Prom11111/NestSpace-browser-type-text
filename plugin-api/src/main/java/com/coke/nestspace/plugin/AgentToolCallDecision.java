package com.coke.nestspace.plugin;

/**
 * Allows a Hook to pass, rewrite or block an Agent tool call.
 */
public final class AgentToolCallDecision {

    public enum Action {
        PROCEED,
        BLOCK
    }

    private final Action action;
    private final String argumentsJson;
    private final String errorMessage;

    private AgentToolCallDecision(
        Action action,
        String argumentsJson,
        String errorMessage
    ) {
        this.action = action;
        this.argumentsJson = argumentsJson;
        this.errorMessage = errorMessage;
    }

    public static AgentToolCallDecision proceed() {
        return new AgentToolCallDecision(Action.PROCEED, null, null);
    }

    public static AgentToolCallDecision withArguments(String argumentsJson) {
        return new AgentToolCallDecision(
            Action.PROCEED,
            argumentsJson,
            null
        );
    }

    public static AgentToolCallDecision block(String errorMessage) {
        return new AgentToolCallDecision(
            Action.BLOCK,
            null,
            errorMessage == null ? "" : errorMessage
        );
    }

    public Action getAction() {
        return action;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}

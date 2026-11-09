package com.coke.nestspace.plugin;

import java.util.Objects;

public final class AgentToolPresentationContext {

    private final String toolName;
    private final String argumentsJson;
    private final String resultText;
    private final boolean successful;
    private final String errorCode;

    public AgentToolPresentationContext(
        String toolName,
        String argumentsJson,
        String resultText,
        boolean successful,
        String errorCode
    ) {
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
        this.resultText = resultText == null ? "" : resultText;
        this.successful = successful;
        this.errorCode = errorCode;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public String getResultText() {
        return resultText;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

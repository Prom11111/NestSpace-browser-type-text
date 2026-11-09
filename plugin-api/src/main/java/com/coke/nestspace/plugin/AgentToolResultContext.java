package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * Completed tool result visible to an after-tool Hook. This Hook is
 * observational by design and cannot rewrite the model-visible result.
 */
public final class AgentToolResultContext {

    private final String toolName;
    private final String argumentsJson;
    private final boolean successful;
    private final String resultText;
    private final String errorCode;

    public AgentToolResultContext(
        String toolName,
        String argumentsJson,
        boolean successful,
        String resultText,
        String errorCode
    ) {
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.argumentsJson = Objects.requireNonNull(
            argumentsJson,
            "argumentsJson"
        );
        this.successful = successful;
        this.resultText = resultText == null ? "" : resultText;
        this.errorCode = errorCode == null ? "" : errorCode;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public String getResultText() {
        return resultText;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

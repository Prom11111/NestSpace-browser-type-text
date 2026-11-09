package com.coke.nestspace.plugin;

import android.content.Context;

import java.util.Objects;

/**
 * Context passed to an Android View factory for one tool result.
 */
public final class PluginToolViewContext {

    private final Context context;
    private final String toolName;
    private final String argumentsJson;
    private final String resultText;
    private final boolean successful;
    private final String errorCode;

    PluginToolViewContext(
        Context context,
        String toolName,
        String argumentsJson,
        String resultText,
        boolean successful,
        String errorCode
    ) {
        this.context = Objects.requireNonNull(context, "context");
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
        this.resultText = resultText == null ? "" : resultText;
        this.successful = successful;
        this.errorCode = errorCode == null ? "" : errorCode;
    }

    /**
     * Use only to construct or inflate the returned Android View. Do not store
     * this context in static fields or retain it after the View is detached.
     */
    public Context getContext() {
        return context;
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

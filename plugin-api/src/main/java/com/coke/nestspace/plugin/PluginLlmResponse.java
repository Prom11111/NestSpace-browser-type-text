package com.coke.nestspace.plugin;

public final class PluginLlmResponse {

    private final String text;
    private final String toolCallsJson;
    private final String stopReason;
    private final int inputTokens;
    private final int outputTokens;

    public PluginLlmResponse(
        String text,
        String toolCallsJson,
        String stopReason,
        int inputTokens,
        int outputTokens
    ) {
        this.text = text == null ? "" : text;
        this.toolCallsJson = toolCallsJson == null || toolCallsJson.isBlank()
            ? "[]"
            : toolCallsJson;
        this.stopReason = stopReason == null ? "" : stopReason;
        this.inputTokens = Math.max(0, inputTokens);
        this.outputTokens = Math.max(0, outputTokens);
    }

    public String getText() {
        return text;
    }

    public String getToolCallsJson() {
        return toolCallsJson;
    }

    public String getStopReason() {
        return stopReason;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }
}

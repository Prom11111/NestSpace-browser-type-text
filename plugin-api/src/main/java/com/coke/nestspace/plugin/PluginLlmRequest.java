package com.coke.nestspace.plugin;

import java.util.Objects;

public final class PluginLlmRequest {

    private final String modelId;
    private final String systemPrompt;
    private final String messagesJson;
    private final String toolsJson;
    private final int maxTokens;
    private final double temperature;

    public PluginLlmRequest(
        String modelId,
        String systemPrompt,
        String messagesJson,
        String toolsJson,
        int maxTokens,
        double temperature
    ) {
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.messagesJson = messagesJson == null ? "[]" : messagesJson;
        this.toolsJson = toolsJson == null ? "[]" : toolsJson;
        this.maxTokens = Math.max(1, maxTokens);
        this.temperature = Math.max(0, Math.min(2, temperature));
    }

    public String getModelId() {
        return modelId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getMessagesJson() {
        return messagesJson;
    }

    public String getToolsJson() {
        return toolsJson;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }
}

package com.coke.nestspace.plugin;

import java.util.Objects;

public final class AgentModelRequestDecision {

    private final AgentModelRequestAction action;
    private final String systemPrompt;
    private final String answer;
    private final String errorMessage;

    private AgentModelRequestDecision(
        AgentModelRequestAction action,
        String systemPrompt,
        String answer,
        String errorMessage
    ) {
        this.action = Objects.requireNonNull(action, "action");
        this.systemPrompt = systemPrompt;
        this.answer = answer;
        this.errorMessage = errorMessage;
    }

    public static AgentModelRequestDecision proceed() {
        return new AgentModelRequestDecision(
            AgentModelRequestAction.PROCEED,
            null,
            null,
            null
        );
    }

    public static AgentModelRequestDecision withSystemPrompt(String prompt) {
        String value = Objects.requireNonNull(prompt, "prompt").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("system prompt is required");
        }
        return new AgentModelRequestDecision(
            AgentModelRequestAction.PROCEED,
            value,
            null,
            null
        );
    }

    public static AgentModelRequestDecision shortCircuit(String answer) {
        String value = Objects.requireNonNull(answer, "answer").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("answer is required");
        }
        return new AgentModelRequestDecision(
            AgentModelRequestAction.SHORT_CIRCUIT,
            null,
            value,
            null
        );
    }

    public static AgentModelRequestDecision block(String message) {
        String value = Objects.requireNonNull(message, "message").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("block message is required");
        }
        return new AgentModelRequestDecision(
            AgentModelRequestAction.BLOCK,
            null,
            null,
            value
        );
    }

    public AgentModelRequestAction getAction() {
        return action;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getAnswer() {
        return answer;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}

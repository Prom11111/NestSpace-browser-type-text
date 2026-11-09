package com.coke.nestspace.plugin;

import java.util.Objects;

public final class AgentQuestionResult {

    private static final AgentQuestionResult NOT_HANDLED =
        new AgentQuestionResult(false, "");

    private final boolean handled;
    private final String answer;

    private AgentQuestionResult(boolean handled, String answer) {
        this.handled = handled;
        this.answer = answer;
    }

    public static AgentQuestionResult notHandled() {
        return NOT_HANDLED;
    }

    public static AgentQuestionResult handled(String answer) {
        String value = Objects.requireNonNull(answer, "answer").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("answer is required");
        }
        return new AgentQuestionResult(true, value);
    }

    public boolean isHandled() {
        return handled;
    }

    public String getAnswer() {
        return answer;
    }
}

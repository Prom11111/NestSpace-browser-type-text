package com.coke.nestspace.plugin;

import java.util.Objects;

public final class AgentToolPresentation {

    private final String title;
    private final String summary;
    private final String detail;
    private final String statusLabel;
    private final String rendererId;
    private final AgentToolViewFactory viewFactory;

    public AgentToolPresentation(
        String title,
        String summary,
        String detail,
        String statusLabel,
        String rendererId
    ) {
        this(
            title,
            summary,
            detail,
            statusLabel,
            rendererId,
            null
        );
    }

    public AgentToolPresentation(
        String title,
        String summary,
        String detail,
        String statusLabel,
        String rendererId,
        AgentToolViewFactory viewFactory
    ) {
        this.title = limit(Objects.requireNonNull(title, "title"), 120);
        this.summary = limit(summary == null ? "" : summary, 500);
        this.detail = limit(detail == null ? "" : detail, 20_000);
        this.statusLabel = limit(
            statusLabel == null ? "" : statusLabel,
            100
        );
        this.rendererId = limit(
            rendererId == null ? "" : rendererId,
            200
        );
        this.viewFactory = viewFactory;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetail() {
        return detail;
    }

    public String getStatusLabel() {
        return statusLabel;
    }

    public String getRendererId() {
        return rendererId;
    }

    public AgentToolViewFactory getViewFactory() {
        return viewFactory;
    }

    private static String limit(String value, int maxChars) {
        String trimmed = value.trim();
        return trimmed.length() <= maxChars
            ? trimmed
            : trimmed.substring(0, maxChars);
    }
}

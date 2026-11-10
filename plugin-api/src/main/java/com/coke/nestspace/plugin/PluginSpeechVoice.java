package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * One voice exposed by a plugin speech provider.
 */
public final class PluginSpeechVoice {

    private final String id;
    private final String displayName;
    private final String locale;
    private final boolean offline;

    public PluginSpeechVoice(
        String id,
        String displayName,
        String locale,
        boolean offline
    ) {
        this.id = limit(Objects.requireNonNull(id, "id"), 160);
        this.displayName = limit(
            Objects.requireNonNull(displayName, "displayName"),
            120
        );
        this.locale = limit(locale == null ? "" : locale, 64);
        this.offline = offline;
        if (this.id.isEmpty()) {
            throw new IllegalArgumentException("speech voice id is required");
        }
        if (this.displayName.isEmpty()) {
            throw new IllegalArgumentException(
                "speech voice displayName is required"
            );
        }
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * BCP-47 language tag, for example zh-CN or en-US. May be empty when the
     * provider resolves the language from the text.
     */
    public String getLocale() {
        return locale;
    }

    public boolean isOffline() {
        return offline;
    }

    private static String limit(String value, int maxChars) {
        String trimmed = value.trim();
        return trimmed.length() <= maxChars
            ? trimmed
            : trimmed.substring(0, maxChars);
    }
}

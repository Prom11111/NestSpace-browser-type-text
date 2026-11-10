package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * A text-to-speech request validated by the host.
 */
public final class PluginSpeechRequest {

    public static final int MAX_TEXT_CHARS = 4_000;
    public static final int SAMPLE_RATE_HZ = 24_000;

    private final String requestId;
    private final String text;
    private final String voiceId;
    private final String locale;
    private final float rate;
    private final float pitch;

    public PluginSpeechRequest(
        String requestId,
        String text,
        String voiceId,
        String locale,
        float rate,
        float pitch
    ) {
        this.requestId = limit(
            Objects.requireNonNull(requestId, "requestId"),
            100
        );
        this.text = Objects.requireNonNull(text, "text").trim();
        this.voiceId = limit(voiceId == null ? "" : voiceId, 160);
        this.locale = limit(locale == null ? "" : locale, 64);
        this.rate = Math.max(0.5f, Math.min(rate, 2.0f));
        this.pitch = Math.max(0.5f, Math.min(pitch, 2.0f));
        if (this.requestId.isEmpty()) {
            throw new IllegalArgumentException("speech requestId is required");
        }
        if (this.text.isEmpty()) {
            throw new IllegalArgumentException("speech text is required");
        }
        if (this.text.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException(
                "speech text exceeds " + MAX_TEXT_CHARS + " characters"
            );
        }
    }

    public String getRequestId() {
        return requestId;
    }

    public String getText() {
        return text;
    }

    public String getVoiceId() {
        return voiceId;
    }

    public String getLocale() {
        return locale;
    }

    public float getRate() {
        return rate;
    }

    public float getPitch() {
        return pitch;
    }

    /**
     * Providers must synthesize PCM signed 16-bit little-endian audio at this
     * sample rate. The host owns playback, routing and audio focus.
     */
    public int getSampleRateHz() {
        return SAMPLE_RATE_HZ;
    }

    private static String limit(String value, int maxChars) {
        String trimmed = value.trim();
        return trimmed.length() <= maxChars
            ? trimmed
            : trimmed.substring(0, maxChars);
    }
}

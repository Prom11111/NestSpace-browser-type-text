package com.coke.nestspace.plugin;

import java.util.Arrays;

/**
 * PCM audio produced by a plugin speech provider.
 */
public final class PluginSpeechAudio {

    public static final int MAX_PCM_BYTES = 16 * 1024 * 1024;
    public static final int MAX_DURATION_MS = 120_000;

    private final byte[] pcm16Le;
    private final int sampleRateHz;
    private final int channels;

    public PluginSpeechAudio(
        byte[] pcm16Le,
        int sampleRateHz,
        int channels
    ) {
        if (pcm16Le == null || pcm16Le.length == 0) {
            throw new IllegalArgumentException("speech PCM audio is required");
        }
        if (pcm16Le.length > MAX_PCM_BYTES) {
            throw new IllegalArgumentException(
                "speech PCM audio exceeds " + MAX_PCM_BYTES + " bytes"
            );
        }
        if (sampleRateHz < 8_000 || sampleRateHz > 48_000) {
            throw new IllegalArgumentException(
                "speech sample rate must be between 8000 and 48000 Hz"
            );
        }
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException(
                "speech audio must be mono or stereo"
            );
        }
        int frameBytes = channels * 2;
        if (pcm16Le.length % frameBytes != 0) {
            throw new IllegalArgumentException(
                "speech PCM audio is not aligned to complete frames"
            );
        }
        long durationMs =
            pcm16Le.length * 1_000L / (sampleRateHz * frameBytes);
        if (durationMs > MAX_DURATION_MS) {
            throw new IllegalArgumentException(
                "speech audio exceeds " + MAX_DURATION_MS + " ms"
            );
        }
        this.pcm16Le = Arrays.copyOf(pcm16Le, pcm16Le.length);
        this.sampleRateHz = sampleRateHz;
        this.channels = channels;
    }

    public byte[] getPcm16Le() {
        return Arrays.copyOf(pcm16Le, pcm16Le.length);
    }

    public int getSampleRateHz() {
        return sampleRateHz;
    }

    public int getChannels() {
        return channels;
    }

    public long getDurationMs() {
        return pcm16Le.length * 1_000L / (sampleRateHz * channels * 2);
    }
}

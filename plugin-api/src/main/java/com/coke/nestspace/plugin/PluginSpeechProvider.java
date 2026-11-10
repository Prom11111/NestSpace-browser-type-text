package com.coke.nestspace.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A plugin-owned text-to-speech engine.
 *
 * The provider owns language handling, voice selection and synthesis. The
 * host owns playback, audio focus, queueing and lifecycle cleanup.
 */
public interface PluginSpeechProvider {

    PluginDescriptor getDescriptor();

    String getProviderId();

    String getDisplayName();

    CompletableFuture<List<PluginSpeechVoice>> listVoicesAsync();

    /**
     * Synthesizes one complete PCM16LE clip.
     *
     * The future completes when synthesis has finished. Calling
     * {@code cancel(true)} must stop the upstream request and release provider
     * resources.
     */
    CompletableFuture<PluginSpeechAudio> synthesizeAsync(
        PluginSpeechRequest request
    );
}

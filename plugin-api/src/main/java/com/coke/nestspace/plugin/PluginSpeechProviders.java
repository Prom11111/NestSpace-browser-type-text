package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local registry of plugin speech providers.
 */
public final class PluginSpeechProviders {

    private static final ConcurrentHashMap<String, PluginSpeechProvider>
        PROVIDERS = new ConcurrentHashMap<>();

    private PluginSpeechProviders() {
    }

    public static void install(PluginSpeechProvider provider) {
        Objects.requireNonNull(provider, "provider");
        PluginDescriptor descriptor = Objects.requireNonNull(
            provider.getDescriptor(),
            "descriptor"
        );
        if (!descriptor.getCapabilities().contains(
            PluginCapability.SPEECH_PROVIDERS
        )) {
            throw new IllegalArgumentException(
                "PluginSpeechProvider requires SPEECH_PROVIDERS"
            );
        }
        String providerId = provider.getProviderId().trim();
        String displayName = provider.getDisplayName().trim();
        if (providerId.isEmpty() || providerId.length() > 160) {
            throw new IllegalArgumentException(
                "speech provider id must contain 1-160 characters"
            );
        }
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException(
                "speech provider displayName is required"
            );
        }
        PluginSpeechProvider previous = PROVIDERS.putIfAbsent(
            providerId,
            provider
        );
        if (previous != null && previous != provider) {
            throw new IllegalArgumentException(
                "speech provider already installed: " + providerId
            );
        }
    }

    public static void uninstall(String providerId) {
        if (providerId != null) {
            PROVIDERS.remove(providerId.trim());
        }
    }

    public static PluginSpeechProvider find(String providerId) {
        if (providerId == null || providerId.isBlank()) return null;
        return PROVIDERS.get(providerId.trim());
    }

    public static List<PluginSpeechProvider> snapshot() {
        List<PluginSpeechProvider> result =
            new ArrayList<>(PROVIDERS.values());
        result.sort(
            Comparator
                .comparingInt(
                    (PluginSpeechProvider provider) ->
                        provider.getDescriptor().getPriority()
                )
                .reversed()
                .thenComparing(PluginSpeechProvider::getProviderId)
        );
        return Collections.unmodifiableList(result);
    }

    public static void clear() {
        PROVIDERS.clear();
    }
}

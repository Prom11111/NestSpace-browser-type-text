package com.coke.nestspace.plugin;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PluginSpeechProvidersTest {

    @After
    public void tearDown() {
        PluginSpeechProviders.clear();
    }

    @Test
    public void installsProvidersByPriority() {
        PluginSpeechProviders.install(provider("provider.low", 10));
        PluginSpeechProviders.install(provider("provider.high", 100));

        assertEquals(
            "provider.high",
            PluginSpeechProviders.snapshot().get(0).getProviderId()
        );
    }

    @Test
    public void rejectsProviderWithoutCapability() {
        assertThrows(IllegalArgumentException.class, () ->
            PluginSpeechProviders.install(new FakeProvider(
                new PluginDescriptor(
                    "com.example.speech",
                    "Speech",
                    "1.0.0",
                    PluginDescriptor.API_VERSION,
                    10,
                    EnumSet.noneOf(PluginCapability.class)
                ),
                "provider.missing-capability"
            ))
        );
    }

    @Test
    public void rejectsDuplicateProviderId() {
        PluginSpeechProviders.install(provider("provider.duplicate", 10));
        assertThrows(IllegalArgumentException.class, () ->
            PluginSpeechProviders.install(
                provider("provider.duplicate", 20)
            )
        );
    }

    private static PluginSpeechProvider provider(String id, int priority) {
        return new FakeProvider(
            new PluginDescriptor(
                "com.example.speech." + id.replace('.', '-'),
                "Speech " + id,
                "1.0.0",
                PluginDescriptor.API_VERSION,
                priority,
                EnumSet.of(PluginCapability.SPEECH_PROVIDERS)
            ),
            id
        );
    }

    private static final class FakeProvider implements PluginSpeechProvider {
        private final PluginDescriptor descriptor;
        private final String providerId;

        private FakeProvider(PluginDescriptor descriptor, String providerId) {
            this.descriptor = descriptor;
            this.providerId = providerId;
        }

        @Override
        public PluginDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public String getProviderId() {
            return providerId;
        }

        @Override
        public String getDisplayName() {
            return descriptor.getName();
        }

        @Override
        public CompletableFuture<List<PluginSpeechVoice>> listVoicesAsync() {
            return CompletableFuture.completedFuture(
                Collections.singletonList(
                    new PluginSpeechVoice("default", "Default", "zh-CN", true)
                )
            );
        }

        @Override
        public CompletableFuture<PluginSpeechAudio> synthesizeAsync(
            PluginSpeechRequest request
        ) {
            return CompletableFuture.completedFuture(
                new PluginSpeechAudio(
                    new byte[] {0, 0, 0, 0},
                    request.getSampleRateHz(),
                    1
                )
            );
        }
    }
}

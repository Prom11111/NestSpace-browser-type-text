package com.coke.nestspace.plugin;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RuntimeProxyPluginTest {

    private final Set<String> pluginIds = new HashSet<>();

    @After
    public void cleanup() {
        for (String pluginId : pluginIds) {
            RuntimeProxyPlugins.uninstall(pluginId);
        }
    }

    @Test
    public void fingerprintIsDeterministicAndIdentitySpecific() {
        String first = RuntimeFingerprints.sha256(
            "identity-a",
            7,
            "signature-a"
        );
        String same = RuntimeFingerprints.sha256(
            "identity-a",
            7,
            "signature-a"
        );
        String other = RuntimeFingerprints.sha256(
            "identity-a",
            8,
            "signature-a"
        );

        assertEquals(first, same);
        assertNotEquals(first, other);
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    public void resolvesMatchingPolicy() {
        String fingerprint = repeat("a", 64);
        install(plugin(
            "test.high",
            10,
            policy(fingerprint, RuntimeLaunchMode.HOST_FALLBACK)
        ));

        RuntimeProxyResolution resolution = RuntimeProxyPlugins.resolve(
            new RuntimeProxyContext(fingerprint, 36)
        );
        assertEquals("test.high", resolution.getPluginId());
        assertEquals(RuntimeLaunchMode.HOST_FALLBACK, resolution.getMode());
    }

    @Test
    public void respectsAndroidApiWindow() {
        String fingerprint = repeat("b", 64);
        install(plugin(
            "test.window",
            0,
            policy(
                fingerprint,
                RuntimeLaunchMode.HOST_FALLBACK,
                30,
                34
            )
        ));

        assertNull(
            RuntimeProxyPlugins.resolve(
                new RuntimeProxyContext(fingerprint, 29)
            )
        );
        assertEquals(
            "test.window",
            RuntimeProxyPlugins.resolve(
                new RuntimeProxyContext(fingerprint, 34)
            ).getPluginId()
        );
        assertNull(
            RuntimeProxyPlugins.resolve(
                new RuntimeProxyContext(fingerprint, 35)
            )
        );
    }

    @Test
    public void rejectsDuplicateFingerprintOwnership() {
        String fingerprint = repeat("c", 64);
        install(plugin(
            "test.owner-a",
            0,
            policy(fingerprint, RuntimeLaunchMode.CONTAINER)
        ));

        assertThrows(IllegalArgumentException.class, () ->
            RuntimeProxyPlugins.install(plugin(
                "test.owner-b",
                0,
                policy(fingerprint, RuntimeLaunchMode.HOST_FALLBACK)
            ))
        );
    }

    private void install(RuntimeProxyPlugin plugin) {
        pluginIds.add(plugin.getDescriptor().getId());
        RuntimeProxyPlugins.install(plugin);
    }

    private static RuntimeProxyPlugin plugin(
        String id,
        int priority,
        RuntimeLaunchPolicy policy
    ) {
        PluginDescriptor descriptor = new PluginDescriptor(
            id,
            id,
            "1",
            PluginDescriptor.API_VERSION,
            priority,
            EnumSet.of(PluginCapability.RUNTIME_LAUNCH_POLICY)
        );
        return new RuntimeProxyPlugin() {
            @Override
            public PluginDescriptor getDescriptor() {
                return descriptor;
            }

            @Override
            public List<RuntimeLaunchPolicy> launchPolicies() {
                return Collections.singletonList(policy);
            }
        };
    }

    private static RuntimeLaunchPolicy policy(
        String fingerprint,
        RuntimeLaunchMode mode
    ) {
        return policy(fingerprint, mode, 24, Integer.MAX_VALUE);
    }

    private static RuntimeLaunchPolicy policy(
        String fingerprint,
        RuntimeLaunchMode mode,
        int minApi,
        int maxApi
    ) {
        return new RuntimeLaunchPolicy(
            "policy",
            fingerprint,
            mode,
            "test policy",
            minApi,
            maxApi
        );
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}

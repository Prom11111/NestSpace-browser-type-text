package com.coke.nestspace.plugin;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class RuntimeProxyPluginTest {

    private final Set<String> pluginIds = new HashSet<>();

    @After
    public void cleanup() {
        for (String pluginId : pluginIds) {
            RuntimeProxyPlugins.uninstall(pluginId);
        }
    }

    @Test
    public void resolvesExactPackageAndVersion() {
        install(plugin(
            "test.high",
            10,
            policy(
                "com.example.target",
                42,
                RuntimeLaunchMode.HOST_FALLBACK
            )
        ));

        RuntimeProxyResolution resolution = RuntimeProxyPlugins.resolve(
            new RuntimeProxyContext("com.example.target", 42, 36)
        );
        assertEquals("test.high", resolution.getPluginId());
        assertEquals("com.example.target", resolution.getPackageName());
        assertEquals(42, resolution.getMatchedVersionCode());
        assertEquals(RuntimeLaunchMode.HOST_FALLBACK, resolution.getMode());
    }

    @Test
    public void exactPolicyDoesNotMatchDifferentPackageOrVersion() {
        install(plugin(
            "test.exact",
            0,
            policy(
                "com.example.target",
                42,
                RuntimeLaunchMode.CONTAINER
            )
        ));

        assertNull(
            RuntimeProxyPlugins.resolve(
                new RuntimeProxyContext("com.example.other", 42, 36)
            )
        );
        assertNull(
            RuntimeProxyPlugins.resolve(
                new RuntimeProxyContext("com.example.target", 43, 36)
            )
        );
    }

    @Test
    public void supportsFuzzyVersionCodeMatching() {
        install(plugin(
            "test.fuzzy",
            0,
            new RuntimeLaunchPolicy(
                "policy",
                "com.example.target",
                RuntimeVersionMatcher.glob("74*"),
                RuntimeLaunchMode.CONTAINER,
                "test fuzzy policy"
            )
        ));

        assertEquals(
            "test.fuzzy",
            RuntimeProxyPlugins.resolve(
                new RuntimeProxyContext("com.example.target", 74_332, 36)
            ).getPluginId()
        );
        assertEquals(
            74_333,
            RuntimeProxyPlugins.resolve(
                new RuntimeProxyContext("com.example.target", 74_333, 36)
            ).getMatchedVersionCode()
        );
        assertNull(
            RuntimeProxyPlugins.resolve(
                new RuntimeProxyContext("com.example.target", 75_000, 36)
            )
        );
    }

    @Test
    public void respectsAndroidApiWindow() {
        install(plugin(
            "test.window",
            0,
            policy(
                "com.example.window",
                7,
                RuntimeLaunchMode.HOST_FALLBACK,
                30,
                34
            )
        ));

        assertNull(
            RuntimeProxyPlugins.resolve(
                new RuntimeProxyContext("com.example.window", 7, 29)
            )
        );
        assertEquals(
            "test.window",
            RuntimeProxyPlugins.resolve(
                new RuntimeProxyContext("com.example.window", 7, 34)
            ).getPluginId()
        );
        assertNull(
            RuntimeProxyPlugins.resolve(
                new RuntimeProxyContext("com.example.window", 7, 35)
            )
        );
    }

    @Test
    public void rejectsDuplicateTargetOwnership() {
        install(plugin(
            "test.owner-a",
            0,
            policy(
                "com.example.shared",
                99,
                RuntimeLaunchMode.CONTAINER
            )
        ));

        assertThrows(IllegalArgumentException.class, () ->
            RuntimeProxyPlugins.install(plugin(
                "test.owner-b",
                0,
                new RuntimeLaunchPolicy(
                    "policy",
                    "com.example.shared",
                    RuntimeVersionMatcher.glob("99*"),
                    RuntimeLaunchMode.HOST_FALLBACK,
                    "overlapping fuzzy policy"
                )
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
        String packageName,
        long versionCode,
        RuntimeLaunchMode mode
    ) {
        return policy(
            packageName,
            versionCode,
            mode,
            24,
            Integer.MAX_VALUE
        );
    }

    private static RuntimeLaunchPolicy policy(
        String packageName,
        long versionCode,
        RuntimeLaunchMode mode,
        int minApi,
        int maxApi
    ) {
        return new RuntimeLaunchPolicy(
            "policy",
            packageName,
            versionCode,
            mode,
            "test policy",
            minApi,
            maxApi
        );
    }
}

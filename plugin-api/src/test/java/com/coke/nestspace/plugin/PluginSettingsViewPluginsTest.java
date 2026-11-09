package com.coke.nestspace.plugin;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import android.view.View;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.EnumSet;

public class PluginSettingsViewPluginsTest {

    @Test
    public void installsAndUninstallsSettingsViewProvider() {
        PluginDescriptor descriptor = descriptor(true);
        PluginContext host = host(descriptor);
        PluginSettingsViewProvider provider =
            new PluginSettingsViewProvider() {
                @Override
                public PluginDescriptor getDescriptor() {
                    return descriptor;
                }

                @Override
                public View createSettingsView(
                    PluginSettingsViewContext context
                ) {
                    return null;
                }
            };

        PluginSettingsViewPlugins.install(provider, host);
        assertSame(provider, PluginSettingsViewPlugins.find(
            descriptor.getId()
        ));
        assertSame(host, PluginSettingsViewPlugins.findHost(
            descriptor.getId()
        ));

        PluginSettingsViewPlugins.uninstall(descriptor.getId());
        assertNull(PluginSettingsViewPlugins.find(descriptor.getId()));
        assertNull(PluginSettingsViewPlugins.findHost(descriptor.getId()));
    }

    @Test
    public void requiresSettingsCapability() {
        PluginDescriptor descriptor = descriptor(false);
        PluginContext host = host(descriptor);
        PluginSettingsViewProvider provider =
            new PluginSettingsViewProvider() {
                @Override
                public PluginDescriptor getDescriptor() {
                    return descriptor;
                }

                @Override
                public View createSettingsView(
                    PluginSettingsViewContext context
                ) {
                    return null;
                }
            };

        assertThrows(
            IllegalArgumentException.class,
            () -> PluginSettingsViewPlugins.install(provider, host)
        );
    }

    private static PluginDescriptor descriptor(boolean settings) {
        return new PluginDescriptor(
            "test.settings",
            "Settings",
            "1.0.0",
            PluginDescriptor.API_VERSION,
            0,
            settings
                ? EnumSet.of(PluginCapability.SETTINGS)
                : EnumSet.noneOf(PluginCapability.class)
        );
    }

    private static PluginContext host(PluginDescriptor descriptor) {
        return (PluginContext) Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[] { PluginContext.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getPluginId":
                        return descriptor.getId();
                    case "getDescriptor":
                        return descriptor;
                    case "toString":
                        return "PluginContext[" + descriptor.getId() + "]";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        throw new UnsupportedOperationException(
                            method.getName()
                        );
                }
            }
        );
    }
}

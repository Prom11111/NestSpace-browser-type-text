package com.coke.nestspace.plugin;

import java.util.Locale;

/**
 * Capability-scoped context for one attached plugin. This is not an Android
 * Context and never exposes Activity, Application or system services.
 */
public interface PluginContext extends PluginHostApi {

    PluginDescriptor getDescriptor();

    PluginManifest getManifest();

    String getHostVersion();

    Locale getLocale();
}

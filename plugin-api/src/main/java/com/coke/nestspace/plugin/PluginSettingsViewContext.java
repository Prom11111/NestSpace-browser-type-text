package com.coke.nestspace.plugin;

import android.content.Context;

import java.util.Objects;

/**
 * Host-owned context for one plugin Settings screen.
 */
public final class PluginSettingsViewContext {

    private final Context context;
    private final PluginContext host;
    private final PluginDescriptor descriptor;
    private final Runnable closeAction;

    PluginSettingsViewContext(
        Context context,
        PluginContext host,
        PluginDescriptor descriptor,
        Runnable closeAction
    ) {
        this.context = Objects.requireNonNull(context, "context");
        this.host = Objects.requireNonNull(host, "host");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
    }

    /**
     * Host factory. Plugin code receives a context instance from the runtime.
     */
    public static PluginSettingsViewContext create(
        Context context,
        PluginContext host,
        PluginDescriptor descriptor,
        Runnable closeAction
    ) {
        return new PluginSettingsViewContext(
            context,
            host,
            descriptor,
            closeAction
        );
    }

    /**
     * Use only to construct or inflate the returned View. Do not retain this
     * Context after the Settings screen closes.
     */
    public Context getContext() {
        return context;
    }

    public PluginContext getHost() {
        return host;
    }

    public PluginDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * Closes the Settings screen and returns to Plugin Center.
     */
    public void close() {
        closeAction.run();
    }
}

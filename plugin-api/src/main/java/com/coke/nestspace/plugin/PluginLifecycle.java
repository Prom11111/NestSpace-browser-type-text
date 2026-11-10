package com.coke.nestspace.plugin;

/**
 * Host services are injected after capability approval and before Hook use.
 * Android facade access is separately gated by ANDROID_APIS.
 */
public interface PluginLifecycle extends PluginMetadata {

    default void onAttach(PluginHostApi host) {
    }

    default void onAttach(PluginContext context) {
        onAttach((PluginHostApi) context);
    }

    default void onDetach() {
    }
}

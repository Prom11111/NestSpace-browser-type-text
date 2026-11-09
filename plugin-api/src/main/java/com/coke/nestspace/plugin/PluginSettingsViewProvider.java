package com.coke.nestspace.plugin;

import android.view.View;

/**
 * Supplies a plugin-owned Android View for its Settings entry.
 *
 * The view is created on the UI thread and may use the encapsulated Android
 * Context only for constructing or inflating the returned hierarchy.
 */
public interface PluginSettingsViewProvider {

    PluginDescriptor getDescriptor();

    View createSettingsView(PluginSettingsViewContext context);

    default void onSettingsViewClosed(View view) {
    }
}

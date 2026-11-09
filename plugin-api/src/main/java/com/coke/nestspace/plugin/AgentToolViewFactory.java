package com.coke.nestspace.plugin;

import android.view.View;

/**
 * Creates a custom Android View for an Agent tool result.
 *
 * The factory is called on the UI thread. The supplied context is scoped to
 * view creation and must not be retained after the returned View is detached.
 */
public interface AgentToolViewFactory {

    View createView(PluginToolViewContext context);
}

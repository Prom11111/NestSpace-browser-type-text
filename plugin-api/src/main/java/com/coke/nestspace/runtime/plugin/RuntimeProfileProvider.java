package com.coke.nestspace.runtime.plugin;

import com.coke.nestspace.plugin.PluginMetadata;

/**
 * Contributes version-bound target Application and Activity hooks.
 */
public interface RuntimeProfileProvider
    extends RuntimeProfileResolver, PluginMetadata {
}

package com.coke.nestspace.runtime.plugin;

import com.coke.nestspace.plugin.PluginDescriptor;

/**
 * Contributes version-bound target Application and Activity hooks.
 */
public interface RuntimeProfileProvider extends RuntimeProfileResolver {

    PluginDescriptor getDescriptor();
}

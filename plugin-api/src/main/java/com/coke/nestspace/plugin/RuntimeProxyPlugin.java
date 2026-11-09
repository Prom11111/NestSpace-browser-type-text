package com.coke.nestspace.plugin;

import java.util.List;

/**
 * Java-first plugin contract. Kotlin implementations use the same interface.
 */
public interface RuntimeProxyPlugin {

    PluginDescriptor getDescriptor();

    List<RuntimeLaunchPolicy> launchPolicies();
}

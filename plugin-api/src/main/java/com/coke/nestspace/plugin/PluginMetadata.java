package com.coke.nestspace.plugin;

/**
 * Provides the descriptor parsed from the plugin's manifest.json.
 *
 * The host binds this value before registering any contribution. Plugins must
 * not duplicate manifest metadata or capabilities in Java code.
 */
public interface PluginMetadata {

    default PluginDescriptor getDescriptor() {
        return PluginMetadataRegistry.require(this);
    }
}

package com.coke.nestspace.plugin;

public interface PluginHostApi {

    String getPluginId();

    PluginAndroidApi android();

    PluginNotificationApi notifications();

    PluginNetworkApi network();

    /**
     * Returns the plugin-scoped WebSocket client.
     */
    default PluginWebSocketApi webSockets() {
        throw new UnsupportedOperationException(
            "WebSocket host API is not available"
        );
    }

    PluginStorageApi storage();

    default PluginSettingsApi settings() {
        throw unsupported("settings");
    }

    default PluginSecretsApi secrets() {
        throw unsupported("secrets");
    }

    default PluginCacheApi cache() {
        throw unsupported("cache");
    }

    default PluginDatabaseApi databases() {
        throw unsupported("databases");
    }

    default PluginFileApi files() {
        throw unsupported("files");
    }

    default PluginCommandApi commands() {
        throw unsupported("commands");
    }

    default PluginTaskApi tasks() {
        throw unsupported("tasks");
    }

    default PluginEventApi events() {
        throw unsupported("events");
    }

    default PluginClipboardApi clipboard() {
        throw unsupported("clipboard");
    }

    default PluginShareApi share() {
        throw unsupported("share");
    }

    default PluginUiApi ui() {
        throw unsupported("ui");
    }

    default PluginLlmApi llm() {
        throw unsupported("llm");
    }

    private UnsupportedOperationException unsupported(String service) {
        return new UnsupportedOperationException(
            "Plugin " + service + " API is not available"
        );
    }
}

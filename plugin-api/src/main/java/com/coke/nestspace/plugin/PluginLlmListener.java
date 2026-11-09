package com.coke.nestspace.plugin;

public interface PluginLlmListener {

    default void onTextDelta(String text) {
    }

    default void onComplete(PluginLlmResponse response) {
    }

    default void onFailure(Throwable error) {
    }
}

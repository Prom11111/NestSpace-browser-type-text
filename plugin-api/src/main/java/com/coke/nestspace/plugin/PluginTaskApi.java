package com.coke.nestspace.plugin;

public interface PluginTaskApi {

    PluginTaskHandle submit(PluginTaskRequest request, PluginTaskBody body);
}

package com.coke.nestspace.plugin;

@FunctionalInterface
public interface PluginTaskBody {

    void run(PluginTaskContext context) throws Exception;
}

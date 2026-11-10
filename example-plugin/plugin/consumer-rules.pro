-keep class com.example.runtimedemo.RuntimeDemoPlugin {
    public <init>();
}
-keep class * implements com.coke.nestspace.plugin.PluginLifecycle { *; }
-keep class * implements com.coke.nestspace.plugin.AgentHookPlugin { *; }
-keep class * implements com.coke.nestspace.plugin.AgentToolProvider { *; }
-keep class * implements com.coke.nestspace.plugin.AgentToolHookPlugin { *; }
-keep class * implements com.coke.nestspace.plugin.AgentToolPresentationPlugin { *; }
-keep class * implements com.coke.nestspace.plugin.PluginCommandProvider { *; }
-keep class * implements com.coke.nestspace.plugin.PluginUiProvider { *; }
-keep class * implements com.coke.nestspace.plugin.PluginLlmProvider { *; }
-keep class * implements com.coke.nestspace.plugin.RuntimeProxyPlugin { *; }
-keep class * implements com.coke.nestspace.runtime.plugin.RuntimeProfileProvider { *; }
-keep class * implements com.coke.nestspace.runtime.plugin.RuntimeProfile { *; }

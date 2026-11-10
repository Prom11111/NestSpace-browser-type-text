-keep interface com.coke.nestspace.plugin.** { *; }
-keep class * implements com.coke.nestspace.plugin.RuntimeProxyPlugin { *; }
-keep class * implements com.coke.nestspace.plugin.RuntimeProxyExtensionProvider { *; }
-keep class * implements com.coke.nestspace.plugin.AgentToolProvider { *; }
-keep class * implements com.coke.nestspace.plugin.AgentToolHookPlugin { *; }
-keep class * implements com.coke.nestspace.plugin.PluginCommandProvider { *; }
-keep class * implements com.coke.nestspace.plugin.PluginUiProvider { *; }
-keep class * implements com.coke.nestspace.plugin.PluginLlmProvider { *; }
-keep class * implements com.coke.nestspace.plugin.PluginSpeechProvider { *; }
-keep class * implements com.coke.nestspace.plugin.AgentHookPlugin { *; }
-keep class * implements com.coke.nestspace.plugin.AgentModelHookPlugin { *; }
-keep class * implements com.coke.nestspace.plugin.AgentToolPresentationPlugin { *; }
-keep class * implements com.coke.nestspace.plugin.PluginLifecycle { *; }
-keep class * implements com.coke.nestspace.plugin.PluginSettingsViewProvider { *; }
-keepclassmembers enum com.coke.nestspace.plugin.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep interface com.coke.nestspace.runtime.plugin.** { *; }
-keep class * implements com.coke.nestspace.runtime.plugin.RuntimeProfile { *; }

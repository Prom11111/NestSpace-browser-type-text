package com.coke.nestspace.plugin;

/**
 * Public capabilities that an external plugin can request.
 */
public enum PluginCapability {
    RUNTIME_LAUNCH_POLICY,
    AGENT_PROMPT,
    AGENT_QUESTION,
    AGENT_MODEL_REQUEST,
    AGENT_TOOLS,
    AGENT_TOOL_HOOKS,
    AGENT_TOOL_PRESENTATION,
    LLM_PROVIDERS,
    COMMANDS,
    TASKS,
    EVENTS,
    SETTINGS,
    SECRETS,
    CACHE,
    DATABASE,
    FILE_PICKER,
    FILE_EXPORT,
    SHARE,
    CLIPBOARD,
    UI_PAGES,
    UI_RENDERERS,
    NOTIFICATIONS,
    NETWORK,
    WEBSOCKET,
    ANDROID_APIS,
    STORAGE,
    RUNTIME_PROXY
}

package com.coke.nestspace.plugin;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Coarse manifest permissions that expand into capability-level checks.
 */
public enum PluginPermission {
    AGENT(
        PluginCapability.AGENT_PROMPT,
        PluginCapability.AGENT_QUESTION,
        PluginCapability.AGENT_MODEL_REQUEST,
        PluginCapability.AGENT_TOOLS,
        PluginCapability.AGENT_TOOL_HOOKS,
        PluginCapability.AGENT_TOOL_PRESENTATION,
        PluginCapability.LLM_PROVIDERS,
        PluginCapability.SPEECH_PROVIDERS
    ),
    RUNTIME(
        PluginCapability.RUNTIME_LAUNCH_POLICY,
        PluginCapability.RUNTIME_HOOKS
    ),
    AUTOMATION(
        PluginCapability.COMMANDS,
        PluginCapability.TASKS,
        PluginCapability.EVENTS
    ),
    STORAGE(
        PluginCapability.STORAGE,
        PluginCapability.CACHE,
        PluginCapability.DATABASE,
        PluginCapability.SETTINGS
    ),
    SECRETS(PluginCapability.SECRETS),
    CONTENT(
        PluginCapability.FILE_PICKER,
        PluginCapability.FILE_EXPORT,
        PluginCapability.SHARE,
        PluginCapability.CLIPBOARD
    ),
    UI(
        PluginCapability.UI_PAGES,
        PluginCapability.UI_RENDERERS
    ),
    NOTIFICATIONS(PluginCapability.NOTIFICATIONS),
    NETWORK(
        PluginCapability.NETWORK,
        PluginCapability.WEBSOCKET
    ),
    DEVICE(PluginCapability.ANDROID_APIS);

    private final Set<PluginCapability> capabilities;

    PluginPermission(PluginCapability... capabilities) {
        Set<PluginCapability> values =
            EnumSet.noneOf(PluginCapability.class);
        Collections.addAll(values, capabilities);
        this.capabilities = Collections.unmodifiableSet(values);
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Set<PluginCapability> capabilities() {
        return capabilities;
    }

    public static PluginPermission fromId(String value) {
        String normalized = normalize(value);
        for (PluginPermission permission : values()) {
            if (permission.id().equals(normalized)) return permission;
        }
        return null;
    }

    public static Set<PluginPermission> groupsFor(
        Set<PluginCapability> capabilities
    ) {
        Set<PluginPermission> result =
            EnumSet.noneOf(PluginPermission.class);
        if (capabilities == null || capabilities.isEmpty()) return result;
        for (PluginPermission permission : values()) {
            for (PluginCapability capability : permission.capabilities) {
                if (capabilities.contains(capability)) {
                    result.add(permission);
                    break;
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static String normalize(String value) {
        return value == null
            ? ""
            : value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_');
    }
}

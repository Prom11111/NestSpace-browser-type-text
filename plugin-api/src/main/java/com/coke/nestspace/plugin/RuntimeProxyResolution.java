package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * Selected launch decision returned to the host launcher.
 */
public final class RuntimeProxyResolution {

    private final String pluginId;
    private final String pluginVersion;
    private final String policyId;
    private final String fingerprint;
    private final RuntimeLaunchMode mode;
    private final String reason;

    RuntimeProxyResolution(
        String pluginId,
        String pluginVersion,
        String policyId,
        String fingerprint,
        RuntimeLaunchMode mode,
        String reason
    ) {
        this.pluginId = pluginId;
        this.pluginVersion = pluginVersion;
        this.policyId = policyId;
        this.fingerprint = fingerprint;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.reason = reason;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getPluginVersion() {
        return pluginVersion;
    }

    public String getPolicyId() {
        return policyId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public RuntimeLaunchMode getMode() {
        return mode;
    }

    public String getReason() {
        return reason;
    }
}

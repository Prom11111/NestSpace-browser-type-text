package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * Selected launch decision returned to the host launcher.
 */
public final class RuntimeProxyResolution {

    private final String pluginId;
    private final String pluginVersion;
    private final String policyId;
    private final String packageName;
    private final RuntimeVersionMatcher versionMatcher;
    private final long matchedVersionCode;
    private final RuntimeLaunchMode mode;
    private final String reason;

    RuntimeProxyResolution(
        String pluginId,
        String pluginVersion,
        String policyId,
        String packageName,
        RuntimeVersionMatcher versionMatcher,
        long matchedVersionCode,
        RuntimeLaunchMode mode,
        String reason
    ) {
        this.pluginId = pluginId;
        this.pluginVersion = pluginVersion;
        this.policyId = policyId;
        this.packageName = packageName;
        this.versionMatcher = versionMatcher;
        this.matchedVersionCode = matchedVersionCode;
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

    public String getPackageName() {
        return packageName;
    }

    public RuntimeVersionMatcher getVersionMatcher() {
        return versionMatcher;
    }

    public long getMatchedVersionCode() {
        return matchedVersionCode;
    }

    public RuntimeLaunchMode getMode() {
        return mode;
    }

    public String getReason() {
        return reason;
    }
}

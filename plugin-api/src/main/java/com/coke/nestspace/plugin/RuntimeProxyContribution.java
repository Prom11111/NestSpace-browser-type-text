package com.coke.nestspace.plugin;

import java.util.Objects;

public final class RuntimeProxyContribution {

    private final String id;
    private final String packageName;
    private final RuntimeVersionMatcher versionMatcher;
    private final String displayName;
    private final String parametersJson;
    private final RuntimeLaunchMode mode;
    private final String reason;

    public RuntimeProxyContribution(
        String id,
        String packageName,
        long versionCode,
        String displayName,
        String parametersJson
    ) {
        this(
            id,
            packageName,
            RuntimeVersionMatcher.exact(versionCode),
            displayName,
            parametersJson,
            RuntimeLaunchMode.CONTAINER,
            displayName
        );
    }

    public RuntimeProxyContribution(
        String id,
        String packageName,
        RuntimeVersionMatcher versionMatcher,
        String displayName,
        String parametersJson
    ) {
        this(
            id,
            packageName,
            versionMatcher,
            displayName,
            parametersJson,
            RuntimeLaunchMode.CONTAINER,
            displayName
        );
    }

    public RuntimeProxyContribution(
        String id,
        String packageName,
        RuntimeVersionMatcher versionMatcher,
        String displayName,
        String parametersJson,
        RuntimeLaunchMode mode,
        String reason
    ) {
        this.id = Objects.requireNonNull(id, "id").trim();
        this.packageName = Objects.requireNonNull(
            packageName,
            "packageName"
        ).trim();
        this.versionMatcher = Objects.requireNonNull(
            versionMatcher,
            "versionMatcher"
        );
        this.displayName = displayName == null ? "" : displayName.trim();
        this.parametersJson = parametersJson == null ||
            parametersJson.isBlank()
            ? "{}"
            : parametersJson;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.reason = reason == null || reason.isBlank()
            ? this.displayName
            : reason;
        if (!this.id.matches("[A-Za-z][A-Za-z0-9_.-]{0,95}")) {
            throw new IllegalArgumentException(
                "invalid runtime proxy contribution id"
            );
        }
        if (this.packageName.isEmpty()) {
            throw new IllegalArgumentException(
                "runtime proxy target must have a package and version"
            );
        }
        if (this.displayName.isEmpty()) {
            throw new IllegalArgumentException(
                "runtime proxy displayName is required"
            );
        }
    }

    public String getId() {
        return id;
    }

    public String getPackageName() {
        return packageName;
    }

    public RuntimeVersionMatcher getVersionMatcher() {
        return versionMatcher;
    }

    public long getMinVersionCode() {
        return versionMatcher.getMinVersionCode();
    }

    public long getMaxVersionCode() {
        return versionMatcher.getMaxVersionCode();
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    public RuntimeLaunchMode getMode() {
        return mode;
    }

    public String getReason() {
        return reason;
    }
}

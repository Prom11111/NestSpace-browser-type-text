package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * Identity facts exposed to the host policy engine.
 */
public final class RuntimeProxyContext {

    private final String packageName;
    private final long versionCode;
    private final int androidApi;

    public RuntimeProxyContext(
        String packageName,
        long versionCode,
        int androidApi
    ) {
        this.packageName = Objects.requireNonNull(
            packageName,
            "packageName"
        ).trim();
        this.versionCode = versionCode;
        this.androidApi = androidApi;
        if (this.packageName.isEmpty()) {
            throw new IllegalArgumentException("packageName is required");
        }
        if (versionCode < 0) {
            throw new IllegalArgumentException("versionCode must be non-negative");
        }
    }

    public String getPackageName() {
        return packageName;
    }

    public long getVersionCode() {
        return versionCode;
    }

    public int getAndroidApi() {
        return androidApi;
    }
}

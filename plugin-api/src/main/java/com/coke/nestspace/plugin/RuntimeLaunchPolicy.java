package com.coke.nestspace.plugin;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A launch policy owned by exactly one runtime proxy plugin.
 */
public final class RuntimeLaunchPolicy {

    private static final Pattern PACKAGE_NAME = Pattern.compile(
        "[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"
    );

    private final String id;
    private final String packageName;
    private final RuntimeVersionMatcher versionMatcher;
    private final RuntimeLaunchMode mode;
    private final String reason;
    private final int minAndroidApi;
    private final int maxAndroidApi;

    public RuntimeLaunchPolicy(
        String id,
        String packageName,
        long versionCode,
        RuntimeLaunchMode mode,
        String reason
    ) {
        this(
            id,
            packageName,
            RuntimeVersionMatcher.exact(versionCode),
            mode,
            reason
        );
    }

    public RuntimeLaunchPolicy(
        String id,
        String packageName,
        RuntimeVersionMatcher versionMatcher,
        RuntimeLaunchMode mode,
        String reason
    ) {
        this(
            id,
            packageName,
            versionMatcher,
            mode,
            reason,
            24,
            Integer.MAX_VALUE
        );
    }

    public RuntimeLaunchPolicy(
        String id,
        String packageName,
        long versionCode,
        RuntimeLaunchMode mode,
        String reason,
        int minAndroidApi,
        int maxAndroidApi
    ) {
        this(
            id,
            packageName,
            RuntimeVersionMatcher.exact(versionCode),
            mode,
            reason,
            minAndroidApi,
            maxAndroidApi
        );
    }

    public RuntimeLaunchPolicy(
        String id,
        String packageName,
        RuntimeVersionMatcher versionMatcher,
        RuntimeLaunchMode mode,
        String reason,
        int minAndroidApi,
        int maxAndroidApi
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
        this.mode = Objects.requireNonNull(mode, "mode");
        this.reason = Objects.requireNonNull(reason, "reason").trim();
        this.minAndroidApi = minAndroidApi;
        this.maxAndroidApi = maxAndroidApi;
        if (!PACKAGE_NAME.matcher(this.packageName).matches()) {
            throw new IllegalArgumentException(
                "packageName must be a valid Android package name"
            );
        }
        if (minAndroidApi > maxAndroidApi) {
            throw new IllegalArgumentException("invalid Android API range");
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

    public RuntimeLaunchMode getMode() {
        return mode;
    }

    public String getReason() {
        return reason;
    }

    public int getMinAndroidApi() {
        return minAndroidApi;
    }

    public int getMaxAndroidApi() {
        return maxAndroidApi;
    }

    public boolean matches(RuntimeProxyContext context) {
        return packageName.equals(context.getPackageName())
            && versionMatcher.matches(context.getVersionCode())
            && context.getAndroidApi() >= minAndroidApi
            && context.getAndroidApi() <= maxAndroidApi;
    }

    boolean overlaps(RuntimeLaunchPolicy other) {
        return packageName.equals(other.packageName) &&
            versionMatcher.mayOverlap(other.versionMatcher);
    }
}

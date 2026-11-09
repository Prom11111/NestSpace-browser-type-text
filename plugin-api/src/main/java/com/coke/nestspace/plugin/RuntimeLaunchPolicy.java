package com.coke.nestspace.plugin;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A launch policy owned by exactly one runtime proxy plugin.
 */
public final class RuntimeLaunchPolicy {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final String id;
    private final String fingerprint;
    private final RuntimeLaunchMode mode;
    private final String reason;
    private final int minAndroidApi;
    private final int maxAndroidApi;

    public RuntimeLaunchPolicy(
        String id,
        String fingerprint,
        RuntimeLaunchMode mode,
        String reason
    ) {
        this(id, fingerprint, mode, reason, 24, Integer.MAX_VALUE);
    }

    public RuntimeLaunchPolicy(
        String id,
        String fingerprint,
        RuntimeLaunchMode mode,
        String reason,
        int minAndroidApi,
        int maxAndroidApi
    ) {
        this.id = Objects.requireNonNull(id, "id").trim();
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint").trim();
        this.mode = Objects.requireNonNull(mode, "mode");
        this.reason = Objects.requireNonNull(reason, "reason").trim();
        this.minAndroidApi = minAndroidApi;
        this.maxAndroidApi = maxAndroidApi;
        if (!SHA256.matcher(this.fingerprint).matches()) {
            throw new IllegalArgumentException(
                "fingerprint must be a lowercase SHA-256 value"
            );
        }
        if (minAndroidApi > maxAndroidApi) {
            throw new IllegalArgumentException("invalid Android API range");
        }
    }

    public String getId() {
        return id;
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

    public int getMinAndroidApi() {
        return minAndroidApi;
    }

    public int getMaxAndroidApi() {
        return maxAndroidApi;
    }

    public boolean matches(RuntimeProxyContext context) {
        return fingerprint.equals(context.getFingerprint())
            && context.getAndroidApi() >= minAndroidApi
            && context.getAndroidApi() <= maxAndroidApi;
    }
}

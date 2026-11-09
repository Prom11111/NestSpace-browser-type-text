package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * Identity facts exposed to the host policy engine.
 *
 * Plugins receive only an opaque fingerprint, never a readable target name or
 * package name.
 */
public final class RuntimeProxyContext {

    private final String fingerprint;
    private final int androidApi;

    public RuntimeProxyContext(String fingerprint, int androidApi) {
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint").trim();
        this.androidApi = androidApi;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public int getAndroidApi() {
        return androidApi;
    }
}

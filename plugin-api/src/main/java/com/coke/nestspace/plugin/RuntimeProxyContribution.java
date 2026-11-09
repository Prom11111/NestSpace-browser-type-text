package com.coke.nestspace.plugin;

import java.util.Objects;

public final class RuntimeProxyContribution {

    private final String id;
    private final String fingerprint;
    private final String displayName;
    private final String parametersJson;
    private final RuntimeLaunchMode mode;
    private final String reason;

    public RuntimeProxyContribution(
        String id,
        String fingerprint,
        String displayName,
        String parametersJson
    ) {
        this(
            id,
            fingerprint,
            displayName,
            parametersJson,
            RuntimeLaunchMode.CONTAINER,
            displayName
        );
    }

    public RuntimeProxyContribution(
        String id,
        String fingerprint,
        String displayName,
        String parametersJson,
        RuntimeLaunchMode mode,
        String reason
    ) {
        this.id = Objects.requireNonNull(id, "id").trim();
        this.fingerprint = Objects.requireNonNull(
            fingerprint,
            "fingerprint"
        ).trim();
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
        if (!this.fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "runtime proxy fingerprint must be SHA-256"
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

    public String getFingerprint() {
        return fingerprint;
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

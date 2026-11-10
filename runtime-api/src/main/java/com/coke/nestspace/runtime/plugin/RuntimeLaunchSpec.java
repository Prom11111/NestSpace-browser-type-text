package com.coke.nestspace.runtime.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Stable, read-only target launch information exposed to runtime plugins.
 */
public final class RuntimeLaunchSpec {

    private final String sessionId;
    private final String spaceId;
    private final String packageName;
    private final String instanceId;
    private final String targetProcessName;
    private final String label;
    private final String baseApkPath;
    private final List<String> splitApkPaths;
    private final String launcherActivity;
    private final long versionCode;
    private final String applicationClassName;
    private final String dataDir;
    private final List<String> nativeAbis;
    private final int androidApi;

    private RuntimeLaunchSpec(Builder builder) {
        this.sessionId = builder.sessionId;
        this.spaceId = builder.spaceId;
        this.packageName = builder.packageName;
        this.instanceId = builder.instanceId;
        this.targetProcessName = builder.targetProcessName;
        this.label = builder.label;
        this.baseApkPath = builder.baseApkPath;
        this.splitApkPaths = immutable(builder.splitApkPaths);
        this.launcherActivity = builder.launcherActivity;
        this.versionCode = builder.versionCode;
        this.applicationClassName = builder.applicationClassName;
        this.dataDir = builder.dataDir;
        this.nativeAbis = immutable(builder.nativeAbis);
        this.androidApi = builder.androidApi;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getSpaceId() {
        return spaceId;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getTargetProcessName() {
        return targetProcessName;
    }

    public String getLabel() {
        return label;
    }

    public String getBaseApkPath() {
        return baseApkPath;
    }

    public List<String> getSplitApkPaths() {
        return splitApkPaths;
    }

    public String getLauncherActivity() {
        return launcherActivity;
    }

    public long getVersionCode() {
        return versionCode;
    }

    public String getApplicationClassName() {
        return applicationClassName;
    }

    public String getDataDir() {
        return dataDir;
    }

    public List<String> getNativeAbis() {
        return nativeAbis;
    }

    public int getAndroidApi() {
        return androidApi;
    }

    public List<String> getApkPaths() {
        List<String> result = new ArrayList<>(splitApkPaths.size() + 1);
        result.add(baseApkPath);
        result.addAll(splitApkPaths);
        return Collections.unmodifiableList(result);
    }

    private static List<String> immutable(List<String> values) {
        return values == null || values.isEmpty()
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static final class Builder {
        private String sessionId = "";
        private String spaceId = "";
        private String packageName = "";
        private String instanceId = "";
        private String targetProcessName = "";
        private String label = "";
        private String baseApkPath = "";
        private List<String> splitApkPaths = Collections.emptyList();
        private String launcherActivity = "";
        private long versionCode;
        private String applicationClassName = "";
        private String dataDir = "";
        private List<String> nativeAbis = Collections.emptyList();
        private int androidApi;

        public Builder sessionId(String value) {
            this.sessionId = safe(value);
            return this;
        }

        public Builder spaceId(String value) {
            this.spaceId = safe(value);
            return this;
        }

        public Builder packageName(String value) {
            this.packageName = Objects.requireNonNull(value, "packageName");
            return this;
        }

        public Builder instanceId(String value) {
            this.instanceId = safe(value);
            return this;
        }

        public Builder targetProcessName(String value) {
            this.targetProcessName = safe(value);
            return this;
        }

        public Builder label(String value) {
            this.label = safe(value);
            return this;
        }

        public Builder baseApkPath(String value) {
            this.baseApkPath = Objects.requireNonNull(value, "baseApkPath");
            return this;
        }

        public Builder splitApkPaths(List<String> value) {
            this.splitApkPaths = value;
            return this;
        }

        public Builder launcherActivity(String value) {
            this.launcherActivity = safe(value);
            return this;
        }

        public Builder versionCode(long value) {
            this.versionCode = value;
            return this;
        }

        public Builder applicationClassName(String value) {
            this.applicationClassName = safe(value);
            return this;
        }

        public Builder dataDir(String value) {
            this.dataDir = safe(value);
            return this;
        }

        public Builder nativeAbis(List<String> value) {
            this.nativeAbis = value;
            return this;
        }

        public Builder androidApi(int value) {
            this.androidApi = value;
            return this;
        }

        public RuntimeLaunchSpec build() {
            if (packageName.isEmpty()) {
                throw new IllegalArgumentException("packageName is required");
            }
            if (baseApkPath.isEmpty()) {
                throw new IllegalArgumentException("baseApkPath is required");
            }
            return new RuntimeLaunchSpec(this);
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }
}

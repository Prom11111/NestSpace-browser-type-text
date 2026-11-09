package com.coke.nestspace.plugin;

import java.util.Objects;

public final class PluginPackageInfo {

    private final String packageName;
    private final String label;

    public PluginPackageInfo(String packageName, String label) {
        this.packageName = Objects.requireNonNull(packageName, "packageName");
        this.label = label == null ? packageName : label;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getLabel() {
        return label;
    }
}

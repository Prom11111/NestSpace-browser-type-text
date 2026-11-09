package com.coke.nestspace.plugin;

final class PluginManifestPaths {

    private PluginManifestPaths() {
    }

    static String requireRelative(String value, String field) {
        String path = value == null ? "" : value.trim().replace('\\', '/');
        if (path.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (path.startsWith("/") ||
            path.contains("\u0000") ||
            java.util.Arrays.asList(path.split("/")).contains("..")) {
            throw new IllegalArgumentException(
                field + " must be a relative package path"
            );
        }
        return path;
    }
}

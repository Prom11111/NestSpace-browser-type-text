package com.coke.nestspace.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Host-owned Android facade. No raw Context or Activity is exposed.
 */
public interface PluginAndroidApi {

    String getHostPackageName();

    int getAndroidApi();

    boolean hasPermission(String permission);

    List<PluginPackageInfo> getLaunchablePackages();

    CompletableFuture<Void> launchPackage(String packageName);

    /**
     * Opens an HTTPS URL in the host's browser. HTTP and other schemes are
     * rejected.
     */
    CompletableFuture<Void> openUrl(String url);
}

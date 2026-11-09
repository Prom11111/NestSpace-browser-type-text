package com.coke.nestspace.plugin;

import java.util.concurrent.CompletableFuture;

public interface PluginNotificationApi {

    CompletableFuture<Void> post(PluginNotification notification);

    CompletableFuture<Void> cancel(String notificationId);
}

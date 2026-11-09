package com.coke.nestspace.plugin;

import java.util.concurrent.CompletableFuture;

public interface PluginNetworkApi {

    CompletableFuture<PluginHttpResponse> execute(PluginHttpRequest request);

    /**
     * Opens a streaming Server-Sent Events connection over HTTP or HTTPS.
     */
    default PluginSseConnection openSse(
        PluginSseRequest request,
        PluginSseListener listener
    ) {
        throw new UnsupportedOperationException(
            "Server-Sent Events are not available"
        );
    }
}

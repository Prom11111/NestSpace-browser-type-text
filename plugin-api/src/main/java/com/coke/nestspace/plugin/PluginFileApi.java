package com.coke.nestspace.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface PluginFileApi {

    CompletableFuture<List<PluginPickedFile>> pickFiles(
        PluginFilePickerRequest request
    );

    CompletableFuture<PluginExportedFile> export(
        PluginFileExportRequest request
    );
}

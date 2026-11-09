package com.coke.nestspace.plugin;

import android.database.sqlite.SQLiteDatabase;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Opens SQLite databases in the plugin's private database directory.
 */
public interface PluginDatabaseApi {

    CompletableFuture<SQLiteDatabase> open(String databaseName);

    CompletableFuture<List<String>> list();

    CompletableFuture<Void> delete(String databaseName);
}

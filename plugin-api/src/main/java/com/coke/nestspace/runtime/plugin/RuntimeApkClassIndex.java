package com.coke.nestspace.runtime.plugin;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexFile;

/**
 * Cached class-name index for target APK files.
 */
@SuppressWarnings("deprecation")
public final class RuntimeApkClassIndex {

    private static final String TAG = "RuntimeApkClassIndex";
    private static final ConcurrentHashMap<String, List<String>> CACHE =
        new ConcurrentHashMap<>();

    private RuntimeApkClassIndex() {
    }

    public static List<String> classNames(List<String> apkPaths) {
        String key = String.join("\u0000", apkPaths);
        List<String> cached = CACHE.get(key);
        if (cached != null) return cached;
        Set<String> names = new LinkedHashSet<>();
        for (String path : apkPaths) {
            DexFile dex = null;
            try {
                dex = new DexFile(path);
                Enumeration<String> entries = dex.entries();
                while (entries.hasMoreElements()) {
                    names.add(entries.nextElement());
                }
            } catch (Throwable error) {
                Log.w(TAG, "open target dex failed: " + path, error);
            } finally {
                if (dex != null) {
                    try {
                        dex.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        List<String> result = Collections.unmodifiableList(
            new ArrayList<>(names)
        );
        List<String> previous = CACHE.putIfAbsent(key, result);
        return previous == null ? result : previous;
    }
}

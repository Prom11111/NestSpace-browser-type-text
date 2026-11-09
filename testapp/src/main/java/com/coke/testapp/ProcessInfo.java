package com.coke.testapp;

import android.app.Application;
import android.os.Build;

import java.io.BufferedReader;
import java.io.FileReader;

final class ProcessInfo {
    private ProcessInfo() {}

    static String name() {
        if (Build.VERSION.SDK_INT >= 28) {
            return Application.getProcessName();
        }
        try (BufferedReader reader = new BufferedReader(
            new FileReader("/proc/self/cmdline")
        )) {
            String value = reader.readLine();
            return value == null ? "?" : value.replace("\0", "");
        } catch (Exception ignored) {
            return "?";
        }
    }
}

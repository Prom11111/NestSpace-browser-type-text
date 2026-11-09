package com.coke.testapp;

public final class NativeProbe {
    static {
        System.loadLibrary("testapp_probe");
    }

    private NativeProbe() {}

    public static void ensureLoaded() {
        // Referencing this class initializes the JNI library before the suite
        // is clicked, allowing the container hook pump to patch its GOT.
    }

    public static native String nativeEnvironmentSummary();
    public static native String nativeOpenPath(String path);
    public static native String nativeFopenPath(String path);
    public static native String nativeSyscallOpenPath(String path);
    public static native String nativeFdPath(String path);
}

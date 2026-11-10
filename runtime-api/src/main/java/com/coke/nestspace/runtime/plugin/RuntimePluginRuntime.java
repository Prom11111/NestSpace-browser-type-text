package com.coke.nestspace.runtime.plugin;

public final class RuntimePluginRuntime {

    private RuntimePluginRuntime() {
    }

    public static <T> T withContainerClassLoader(
        ClassLoader loader,
        RuntimeClassLoaderScope<T> scope
    ) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return scope.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}

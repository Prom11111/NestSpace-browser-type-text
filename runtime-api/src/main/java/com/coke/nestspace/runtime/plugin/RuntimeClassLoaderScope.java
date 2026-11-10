package com.coke.nestspace.runtime.plugin;

@FunctionalInterface
public interface RuntimeClassLoaderScope<T> {

    T run();
}

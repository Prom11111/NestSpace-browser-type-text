package com.coke.nestspace.runtime.plugin;

@FunctionalInterface
public interface RuntimeProfileResolver {

    RuntimeProfile resolve(
        RuntimeLaunchSpec spec,
        ClassLoader loader
    );
}

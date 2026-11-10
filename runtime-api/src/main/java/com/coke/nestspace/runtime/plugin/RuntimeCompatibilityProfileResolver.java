package com.coke.nestspace.runtime.plugin;

@FunctionalInterface
public interface RuntimeCompatibilityProfileResolver {

    RuntimeCompatibilityProfile resolve(
        RuntimeCompatLaunchSpec spec,
        ClassLoader loader
    );
}

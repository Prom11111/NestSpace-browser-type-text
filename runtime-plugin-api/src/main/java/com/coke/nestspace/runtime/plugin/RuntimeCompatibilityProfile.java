package com.coke.nestspace.runtime.plugin;

import android.app.Activity;
import android.app.Application;
import android.content.Context;

/**
 * Version-bound hooks contributed by a runtime compatibility plugin.
 */
public interface RuntimeCompatibilityProfile {

    String getId();

    default void beforeApplicationAttach(
        RuntimeCompatLaunchSpec spec,
        ClassLoader loader,
        Context context
    ) {
    }

    default void afterApplicationCreated(
        RuntimeCompatLaunchSpec spec,
        ClassLoader loader,
        Context context,
        Application application
    ) {
    }

    default void afterEmbeddedApplicationCreated(
        RuntimeCompatLaunchSpec spec,
        ClassLoader loader,
        Context context,
        Application application
    ) {
    }

    default void beforeTargetActivity(
        RuntimeCompatLaunchSpec spec,
        ClassLoader loader,
        Context context,
        Class<? extends Activity> activityClass
    ) {
    }

    default AutoCloseable installFirstActivityHooks(
        RuntimeCompatLaunchSpec spec,
        ClassLoader loader,
        Context context
    ) {
        return null;
    }
}

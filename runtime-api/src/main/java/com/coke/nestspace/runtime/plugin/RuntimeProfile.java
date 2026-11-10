package com.coke.nestspace.runtime.plugin;

import android.app.Activity;
import android.app.Application;
import android.content.Context;

/**
 * Version-bound hooks contributed by a runtime profile plugin.
 */
public interface RuntimeProfile {

    String getId();

    default void beforeApplicationAttach(
        RuntimeLaunchSpec spec,
        ClassLoader loader,
        Context context
    ) {
    }

    default void afterApplicationCreated(
        RuntimeLaunchSpec spec,
        ClassLoader loader,
        Context context,
        Application application
    ) {
    }

    default void afterEmbeddedApplicationCreated(
        RuntimeLaunchSpec spec,
        ClassLoader loader,
        Context context,
        Application application
    ) {
    }

    default void beforeTargetActivity(
        RuntimeLaunchSpec spec,
        ClassLoader loader,
        Context context,
        Class<? extends Activity> activityClass
    ) {
    }

    default AutoCloseable installFirstActivityHooks(
        RuntimeLaunchSpec spec,
        ClassLoader loader,
        Context context
    ) {
        return null;
    }
}

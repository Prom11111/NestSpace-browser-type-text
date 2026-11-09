package com.coke.nestspace.container.compat

import android.app.Application
import android.content.Context
import com.coke.nestspace.container.ContainerLaunchSpec

/**
 * Version-bound compatibility hooks implemented by runtime-compat.
 *
 * The generic container lifecycle owns only this contract and never imports a
 * concrete profile implementation.
 */
interface CompatibilityProfile {
    val id: String

    fun beforeApplicationAttach(
        spec: ContainerLaunchSpec,
        loader: ClassLoader,
        context: Context
    ) = Unit

    /** Runs immediately after the target Application.onCreate returns. */
    fun afterApplicationCreated(
        spec: ContainerLaunchSpec,
        loader: ClassLoader,
        context: Context,
        application: Application
    ) = Unit

    /** Runs after an embedded Tinker/AppLike bootstrap has been created. */
    fun afterEmbeddedApplicationCreated(
        spec: ContainerLaunchSpec,
        loader: ClassLoader,
        context: Context,
        application: Application
    ) = Unit

    /** Runs immediately before a target Activity class is instantiated. */
    fun beforeTargetActivity(
        spec: ContainerLaunchSpec,
        loader: ClassLoader,
        context: Context,
        activityClass: Class<out android.app.Activity>
    ) = Unit

    fun installFirstActivityHooks(
        spec: ContainerLaunchSpec,
        loader: ClassLoader,
        context: Context
    ): AutoCloseable? = null
}

object CompatibilityProfiles {
    @Volatile
    private var resolver: (ContainerLaunchSpec, ClassLoader) -> CompatibilityProfile? =
        { _, _ -> null }

    fun register(
        resolver: (ContainerLaunchSpec, ClassLoader) -> CompatibilityProfile?
    ) {
        this.resolver = resolver
    }

    fun resolve(
        spec: ContainerLaunchSpec,
        loader: ClassLoader
    ): CompatibilityProfile? = resolver(spec, loader)
}

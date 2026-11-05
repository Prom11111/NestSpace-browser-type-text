package com.coke.nestspace.container.identity

import com.coke.nestspace.BuildConfig

/**
 * The real host package. Context identity inside a container may be rewritten
 * to the target app, so never derive the host package from a Context.
 */
object HostIdentity {
    val packageName: String = BuildConfig.APPLICATION_ID
}

package com.coke.nestspace.runtime

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Runtime data shared by the container and target Application factory.
 *
 * Host hooks remain here because target class loaders call into the host while
 * avoiding a compile-time dependency on UI implementation classes.
 */
data class RuntimeApplicationSpec(
    val packageName: String,
    val applicationInfo: ApplicationInfo,
    val baseApkPath: String,
    val splitApkPaths: List<String>
)

object RuntimeHostIdentity {
    @Volatile
    var packageName: String = "com.coke.nestspace"
}

object RuntimeHostHooks {
    @Volatile
    var openBrowser: ((Context, String) -> Unit)? = null

    @Volatile
    var setOverlayHidden: ((Context, String, Boolean) -> Unit)? = null

    @Volatile
    var publishContainerMode: ((Context, Boolean) -> Unit)? = null

    @Volatile
    var requestDesktop: (() -> Unit)? = null
}

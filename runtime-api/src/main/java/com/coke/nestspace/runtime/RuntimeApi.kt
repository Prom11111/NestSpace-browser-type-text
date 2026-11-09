package com.coke.nestspace.runtime

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Narrow process-boundary contracts shared by the runtime implementations.
 *
 * No container implementation, lifecycle helper or process-pool state belongs
 * here. The app installs plugins; each runtime owns its implementation.
 */
data class RuntimeApplicationSpec(
    val packageName: String,
    val applicationInfo: ApplicationInfo,
    val baseApkPath: String,
    val splitApkPaths: List<String>
)

interface RuntimeApplicationFactory {
    fun supports(spec: RuntimeApplicationSpec): Boolean = true

    fun create(
        spec: RuntimeApplicationSpec,
        loader: ClassLoader,
        containerContext: Context
    ): Application?
}

object RuntimeApplicationFactories {
    @Volatile
    var compat: RuntimeApplicationFactory? = null
}

interface RuntimePluginInstaller {
    fun install()
}

/**
 * The app composition root installs runtime backends by class name so a
 * native-only build can omit the compatibility module entirely.
 */
object RuntimePluginLoader {
    private val builtIns = listOf(
        "com.coke.nestspace.container.compat.CompatRuntimePlugin"
    )

    fun installBuiltIns() {
        builtIns.forEach { className ->
            runCatching {
                val type = Class.forName(className)
                type.getDeclaredConstructor().newInstance() as RuntimePluginInstaller
            }.getOrNull()?.install()
        }
    }
}

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

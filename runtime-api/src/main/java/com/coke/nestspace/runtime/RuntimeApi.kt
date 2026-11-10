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
    var target: RuntimeApplicationFactory? = null
}

interface RuntimePluginInstaller {
    fun install()

    fun uninstall() {
    }
}

/**
 * The app composition root installs runtime backends by class name so the API
 * module does not need a compile-time dependency on runtime-core.
 */
object RuntimePluginLoader {
    private val installed = LinkedHashMap<String, RuntimePluginInstaller>()

    private val coreInstallers = listOf(
        "com.coke.nestspace.container.application.ApplicationRuntimeInstaller"
    )

    @Synchronized
    fun installCore() {
        installClasses(coreInstallers)
    }

    @Synchronized
    fun installBuiltIns() {
        installClasses(coreInstallers)
    }

    @Synchronized
    fun uninstallBuiltIns() {
        uninstallClasses(coreInstallers.asReversed())
    }

    @Synchronized
    fun isCoreInstalled(): Boolean = coreInstallers.any(installed::containsKey)

    @Synchronized
    fun isInstalled(): Boolean = installed.isNotEmpty()

    private fun installClasses(classNames: List<String>) {
        classNames.forEach { className ->
            if (installed.containsKey(className)) return@forEach
            val plugin = runCatching {
                val type = Class.forName(className)
                type.getDeclaredConstructor().newInstance() as RuntimePluginInstaller
            }.getOrNull() ?: return@forEach
            runCatching { plugin.install() }.onSuccess {
                installed[className] = plugin
            }
        }
    }

    private fun uninstallClasses(classNames: List<String>) {
        classNames.forEach { className ->
            installed.remove(className)?.let { plugin ->
                runCatching { plugin.uninstall() }
            }
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

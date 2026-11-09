package com.coke.nestspace.container.compat.p74332

import android.app.Application
import android.content.Context
import com.coke.nestspace.container.ContainerLaunchSpec
import com.coke.nestspace.container.compat.CompatibilityProfile
import com.coke.nestspace.container.loader.ApkClassIndex
import java.security.MessageDigest

/**
 * Versioned profile selected entirely by opaque class-name hashes.
 *
 * No target package, brand, or business class name is readable in the generic
 * runtime. Runtime DEX names are hashed and matched against these constants.
 */
internal object ProfileP74332 {

    const val VERSION_CODE = 74_332L

    internal const val TOOL_UTILS =
        "2679efd969435ec70f715fd310c319d84ffdba5c11a6607ff4856dd04e978885"
    internal const val ACCOUNT_CONFIG =
        "ac4b81fb2e1b5cc5996b55801e427dbba121b7565ceee4c32ade4a4fba729267"
    internal const val ACCOUNT_STATE =
        "1563e4bc63b56b0c341c9bfae3f448c8a47898ceaaca65911b1d6b77888b0fa8"
    internal const val MIGRATION_STATE =
        "af58d2efa956197edee8194055131f2917606314f075e3ff3e162ca36a9eb750"
    internal const val SERVICE_MANAGER =
        "ae50d3c72cafa22f46ea7a530de698826170c145221064486221948586465d8b"
    internal const val SHORT_SERIES_API =
        "dabbfde9e27bb85f3d85898d725d1b2f14d339108eca3663f8233c0e431ad959"
    internal const val SHORT_SERIES_IMPL =
        "a5bdff87a6ffeae523ef33f8dc3eaff56a934e02d49fcf0b007839df285cd5cc"
    internal const val SHORT_SERIES_DOCKER =
        "45648499f2542a9ad8a27a81f1ce59a651b016824b8f763a24582a79d339c588"
    internal const val SHORT_SERIES_DEVICE =
        "a5b059242454094df736407398c0d0b4311799e4bf6e848391d36786c62ad256"
    internal const val SHORT_SERIES_DEVICE_IMPL =
        "2c0b83604864960bbd770399d3ed1397a5cd0aa950c3d1bdf09be799245c3f16"
    internal const val SHORT_SERIES_ACTIVITY =
        "d7c8141fdd10b18808f9d0635f795f1c06ae6068f05b44b5f8e61fb09a8aaca3"
    internal const val RPC_STARTUP_TASK =
        "991806f47574a9295d4a442f645ff25d5b3df3bca1ce77241c6480e0ef02944d"
    internal const val NETWORK_STARTUP_TASK =
        "99bf9b95d61de0a09a1553a419c119b3c490d619ca5be247befa0b8d3e8336e0"
    internal const val IMAGE_STARTUP_TASK =
        "e0e74c426cde9a83da921080b0acc1ceb0b773eb830e7beba8ceb09c5c815b94"
    internal const val TTNET_INIT =
        "13090f527ba5a372e9cf7d5d769ee1df37f6cd5697f02d6b173254bcfd27a2e9"
    internal const val SINGLE_APP_CONTEXT =
        "6417f48017af0ee1d97e4a4afbdea00d9e37c5afd2d050b852ab61dad569a096"
    internal const val APP_LOG_INITIALIZER =
        "27149eac0256084a7beca81733aff703eb54ede631ab3492e867ebea903d3afc"
    internal const val REQUEST_API =
        "2c9e836cc5ff0487ede9b21ce342938714092c3e5976d66679069c6f12d321c5"
    internal const val REQUEST_HOLDER =
        "13bee39170aaf80c4a890d39d4fa5cc1db0666b2f6bcdd59104909fc61229e4c"
    internal const val REQUEST_UPDATER =
        "0f7d917e866e38a38ff8d8b4ff0fb456551066a46fb4ec411a9000bc8a8533a8"

    internal val requiredHashes = listOf(
        TOOL_UTILS,
        ACCOUNT_CONFIG,
        ACCOUNT_STATE,
        MIGRATION_STATE,
        SERVICE_MANAGER,
        SHORT_SERIES_API,
        SHORT_SERIES_IMPL,
        SHORT_SERIES_DOCKER,
        SHORT_SERIES_DEVICE,
        SHORT_SERIES_DEVICE_IMPL,
        SHORT_SERIES_ACTIVITY,
        RPC_STARTUP_TASK,
        NETWORK_STARTUP_TASK,
        IMAGE_STARTUP_TASK,
        TTNET_INIT,
        SINGLE_APP_CONTEXT,
        APP_LOG_INITIALIZER,
        REQUEST_API,
        REQUEST_HOLDER,
        REQUEST_UPDATER
    )

    val id: String by lazy { "p-${digest(requiredHashes.sorted().joinToString("\n")).take(12)}" }

    data class Classes(
        val toolUtils: Class<*>,
        val accountConfig: Class<*>,
        val accountState: Class<*>,
        val migrationState: Class<*>,
        val serviceManager: Class<*>,
        val shortSeriesApi: Class<*>,
        val shortSeriesImpl: Class<*>,
        val shortSeriesDocker: Class<*>,
        val shortSeriesDevice: Class<*>,
        val shortSeriesDeviceImpl: Class<*>,
        val rpcStartupTask: Class<*>,
        val networkStartupTask: Class<*>,
        val imageStartupTask: Class<*>,
        val ttnetInit: Class<*>,
        val singleAppContext: Class<*>,
        val appLogInitializer: Class<*>,
        val requestApi: Class<*>,
        val requestHolder: Class<*>,
        val requestUpdater: Class<*>
    )

    fun resolve(
        spec: ContainerLaunchSpec,
        loader: ClassLoader
    ): CompatibilityProfile? {
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            spec.packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            spec.packageInfo.versionCode.toLong()
        }
        if (versionCode != VERSION_CODE) return null
        val byHash = ApkClassIndex.classNames(
            listOf(spec.baseApkPath) + spec.splitApkPaths
        ).associateBy(::digest)
        if (requiredHashes.any { it !in byHash }) return null

        fun load(hash: String): Class<*>? = byHash[hash]
            ?.let { name -> runCatching { loader.loadClass(name) }.getOrNull() }
            ?.takeIf { it.classLoader === loader }

        val classes = Classes(
            toolUtils = load(TOOL_UTILS) ?: return null,
            accountConfig = load(ACCOUNT_CONFIG) ?: return null,
            accountState = load(ACCOUNT_STATE) ?: return null,
            migrationState = load(MIGRATION_STATE) ?: return null,
            serviceManager = load(SERVICE_MANAGER) ?: return null,
            shortSeriesApi = load(SHORT_SERIES_API) ?: return null,
            shortSeriesImpl = load(SHORT_SERIES_IMPL) ?: return null,
            shortSeriesDocker = load(SHORT_SERIES_DOCKER) ?: return null,
            shortSeriesDevice = load(SHORT_SERIES_DEVICE) ?: return null,
            shortSeriesDeviceImpl = load(SHORT_SERIES_DEVICE_IMPL) ?: return null,
            rpcStartupTask = load(RPC_STARTUP_TASK) ?: return null,
            networkStartupTask = load(NETWORK_STARTUP_TASK) ?: return null,
            imageStartupTask = load(IMAGE_STARTUP_TASK) ?: return null,
            ttnetInit = load(TTNET_INIT) ?: return null,
            singleAppContext = load(SINGLE_APP_CONTEXT) ?: return null,
            appLogInitializer = load(APP_LOG_INITIALIZER) ?: return null,
            requestApi = load(REQUEST_API) ?: return null,
            requestHolder = load(REQUEST_HOLDER) ?: return null,
            requestUpdater = load(REQUEST_UPDATER) ?: return null
        )
        return ResolvedProfileP74332(classes)
    }

    internal fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

private class ResolvedProfileP74332(
    private val classes: ProfileP74332.Classes
) : CompatibilityProfile {

    override val id: String = ProfileP74332.id

    override fun beforeApplicationAttach(
        spec: ContainerLaunchSpec,
        loader: ClassLoader,
        context: Context
    ) {
        ProcessIdentitySeedHook.install(classes, loader, spec.targetProcessName)
        ServiceRegistrySeedHook.install(classes, loader, context, initializeDocker = false)
    }

    override fun afterApplicationCreated(
        spec: ContainerLaunchSpec,
        loader: ClassLoader,
        context: Context,
        application: Application
    ) {
        ServiceRegistrySeedHook.install(classes, loader, context, initializeDocker = true)
    }

    override fun afterEmbeddedApplicationCreated(
        spec: ContainerLaunchSpec,
        loader: ClassLoader,
        context: Context,
        application: Application
    ) {
        StartupTaskHook.ensureRpc(classes, loader, application)
        StartupTaskHook.ensureNetwork(classes, loader, application)
    }

    override fun installFirstActivityHooks(
        spec: ContainerLaunchSpec,
        loader: ClassLoader,
        context: Context
    ): AutoCloseable? = RequestSnapshotHook.install(classes, loader, context)

    override fun beforeTargetActivity(
        spec: ContainerLaunchSpec,
        loader: ClassLoader,
        context: Context,
        activityClass: Class<out android.app.Activity>
    ) {
        if (ProfileP74332.digest(activityClass.name) !=
            ProfileP74332.SHORT_SERIES_ACTIVITY
        ) {
            return
        }
        ServiceRegistrySeedHook.install(
            classes,
            loader,
            context,
            initializeDocker = true
        )
    }
}

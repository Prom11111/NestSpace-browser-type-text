package com.coke.nestspace.container.vpm

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import com.coke.nestspace.container.identity.ContainerIdentity
import com.coke.nestspace.container.intent.ContainerIntentResolver
import com.coke.nestspace.container.loader.ApkDescriptor
import com.coke.nestspace.container.loader.ApkInspector
import com.coke.nestspace.engine.SpaceStore
import java.io.File

/**
 * Complete VPMS support for packages installed in the space.
 *
 * A container session knows its own APK descriptor, but until now every other
 * query fell through to the host PackageManager, where virtual packages do not
 * exist. This registry reads the persistent install records and answers
 * cross-virtual-app queries:
 *
 * - getInstalledPackages / getInstalledApplications include every app in the
 *   same space, with synthetic per-package UIDs;
 * - explicit component queries and implicit Intent resolution see the other
 *   apps' Activities/Services/Providers/Receivers;
 * - labels, icons, resources, launch Intents and permission declarations are
 *   answered from the target APK.
 *
 * The install index is loaded cheaply (state.json) so "is this package
 * virtual?" does not parse APKs; each package's manifest/resources are
 * inspected lazily on the first query that needs component details.
 *
 * The current app is intentionally excluded: its richer live descriptor stays
 * owned by ContainerPackageManager.
 */
class VirtualPackageRegistry(
    context: Context,
    private val currentPackage: String,
    private val currentInstanceId: String,
    private val spaceId: String,
    private val contentResolver: () -> ContentResolver?
) {

    private class Record(
        val packageName: String,
        val label: String,
        val baseApkPath: String,
        val splitApkPaths: List<String>,
        val launcherActivity: String,
        val versionName: String?,
        val versionCode: Long,
        val signatureSha256: String?,
        val instanceId: String
    )

    private class Entry(
        val packageName: String,
        val label: CharSequence,
        val descriptor: ApkDescriptor,
        val resources: Resources?,
        val resolver: ContainerIntentResolver,
        val uid: Int
    ) {
        val applicationInfo: ApplicationInfo get() = descriptor.applicationInfo
        val packageInfo: PackageInfo get() = descriptor.packageInfo
    }

    private val hostContext: Context = context.applicationContext
    private val records = LinkedHashMap<String, Record>()
    private val entries = HashMap<String, Entry>()
    private var recordsLoaded = false

    /**
     * Inspecting an APK loads its application label through the container
     * PackageManager, which can route back into this registry for the same
     * package (label resource lookup). Without this guard the entry load would
     * recurse into itself. The nested lookup returns null and the caller falls
     * back to the descriptor's own label.
     */
    private val loading = HashSet<String>()

    @Synchronized
    fun allNames(): List<String> {
        ensureRecords()
        return records.keys.toList()
    }

    @Synchronized
    fun allPackages(): List<PackageInfo> {
        ensureRecords()
        return records.values.map { toMinimalPackageInfo(it) }
    }

    @Synchronized
    fun allApplications(): List<ApplicationInfo> {
        ensureRecords()
        return records.values.map { toMinimalApplicationInfo(it) }
    }

    @Synchronized
    fun isVirtualPackage(packageName: String?): Boolean {
        if (packageName == null) return false
        if (packageName == currentPackage) return true
        ensureRecords()
        return records.containsKey(packageName)
    }

    @Synchronized
    fun packageUid(packageName: String?): Int? {
        if (packageName == null) return null
        if (packageName == currentPackage) return ContainerIdentity.hostUid()
        ensureRecords()
        return if (records.containsKey(packageName)) {
            ContainerIdentity.virtualUidFor(packageName)
        } else {
            null
        }
    }

    /** Instance whose package metadata is exposed for a virtual package. */
    @Synchronized
    fun instanceIdFor(packageName: String?): String? {
        if (packageName == null) return null
        if (packageName == currentPackage) return currentInstanceId
        return record(packageName)?.instanceId
    }

    @Synchronized
    fun packageForUid(uid: Int): String? {
        if (uid == ContainerIdentity.hostUid()) return currentPackage
        ensureRecords()
        return records.keys.firstOrNull { ContainerIdentity.virtualUidFor(it) == uid }
    }

    /** Lightweight metadata from the install index; does not parse the APK. */
    @Synchronized
    fun findPackageInfo(packageName: String?): PackageInfo? =
        record(packageName)?.let { toMinimalPackageInfo(it) }

    /** Lightweight metadata from the install index; does not parse the APK. */
    @Synchronized
    fun findApplicationInfo(packageName: String?): ApplicationInfo? =
        record(packageName)?.let { toMinimalApplicationInfo(it) }

    /** Full descriptor-backed PackageInfo (components/permissions). */
    @Synchronized
    fun findFullPackageInfo(packageName: String?): PackageInfo? =
        entry(packageName)?.packageInfo

    /** Full descriptor-backed ApplicationInfo (icon/theme/meta-data). */
    @Synchronized
    fun findFullApplicationInfo(packageName: String?): ApplicationInfo? =
        entry(packageName)?.applicationInfo

    @Synchronized
    fun findActivityInfo(component: ComponentName?): ActivityInfo? {
        if (component == null) return null
        val entry = entry(component.packageName) ?: return null
        return entry.resolver.findActivity(component.className)
    }

    @Synchronized
    fun findServiceInfo(component: ComponentName?): ServiceInfo? {
        if (component == null) return null
        val entry = entry(component.packageName) ?: return null
        return entry.resolver.findService(component.className)
    }

    @Synchronized
    fun findProviderInfo(component: ComponentName?): ProviderInfo? {
        if (component == null) return null
        val entry = entry(component.packageName) ?: return null
        val wanted = entry.resolver.normalize(component.className)
        return entry.descriptor.providers.firstOrNull {
            entry.resolver.normalize(it.name) == wanted
        }
    }

    @Synchronized
    fun findReceiverInfo(component: ComponentName?): ActivityInfo? {
        if (component == null) return null
        val entry = entry(component.packageName) ?: return null
        return entry.resolver.findReceiver(component.className)
    }

    @Synchronized
    fun findProviderByAuthority(authority: String?): ProviderInfo? {
        if (authority.isNullOrBlank()) return null
        val packageName = packageForAuthority(authority) ?: return null
        return entry(packageName)?.resolver?.findProvider(authority)
    }

    @Synchronized
    fun findProviderForIntent(intent: Intent?): ProviderInfo? {
        if (intent == null) return null
        val packageName = packageForAuthority(intent.data?.authority) ?: return null
        return entry(packageName)?.resolver?.providerMatches(intent)
    }

    @Synchronized
    fun labelFor(packageName: String?): CharSequence? = record(packageName)?.label

    @Synchronized
    fun resourcesFor(packageName: String?): Resources? = entry(packageName)?.resources

    @Synchronized
    fun iconFor(packageName: String?): Drawable? =
        drawableFromEntry(entry(packageName))

    /**
     * Icon only when the APK was already inspected for another reason. Used by
     * getInstalledApplications()-style enumeration so loading the list does not
     * parse every virtual APK.
     */
    @Synchronized
    fun cachedIconFor(packageName: String?): Drawable? {
        if (packageName == null || packageName == currentPackage) return null
        ensureRecords()
        return drawableFromEntry(entries[packageName])
    }

    private fun drawableFromEntry(entry: Entry?): Drawable? {
        val resources = entry?.resources ?: return null
        val id = entry.applicationInfo.icon
        if (id == 0) return null
        return runCatching { resources.getDrawable(id, null) }.getOrNull()
    }

    /**
     * Built from the install index, so launching an app in the space does not
     * require parsing its APK (or every other app's APK).
     */
    @Synchronized
    fun launchIntentFor(packageName: String?): Intent? {
        val record = record(packageName) ?: return null
        val activity = record.launcherActivity.takeIf { it.isNotBlank() } ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(record.packageName, activity)
    }

    // Unscoped implicit-Intent resolution intentionally does not scan every
    // installed virtual app: parsing large APKs (Zhihu/NetEase are 100MB+)
    // inside a startup PM query blocks the container's main thread. Only an
    // Intent explicitly scoped to a virtual package (component or setPackage)
    // is answered; cross-app lookups stay cheap and predictable.
    @Synchronized
    fun queryActivities(intent: Intent?, flags: Int): List<ResolveInfo> {
        if (intent == null) return emptyList()
        val entry = scopedEntry(intent) ?: return emptyList()
        return entry.resolver.queryActivities(intent, flags).map {
            entry.resolver.activityResolveInfo(it)
        }
    }

    @Synchronized
    fun queryServices(intent: Intent?, flags: Int): List<ResolveInfo> {
        if (intent == null) return emptyList()
        val entry = scopedEntry(intent) ?: return emptyList()
        return entry.resolver.queryServices(intent, flags).map {
            entry.resolver.serviceResolveInfo(it)
        }
    }

    @Synchronized
    fun queryReceivers(intent: Intent?, flags: Int): List<ResolveInfo> {
        if (intent == null) return emptyList()
        val entry = scopedEntry(intent) ?: return emptyList()
        return entry.resolver.queryReceivers(intent, flags).map {
            entry.resolver.activityResolveInfo(it)
        }
    }

    @Synchronized
    fun queryProviders(intent: Intent?, flags: Int): List<ResolveInfo> {
        if (intent == null) return emptyList()
        val entry = scopedEntry(intent) ?: return emptyList()
        return entry.resolver.providerMatches(intent)?.let {
            listOf(entry.resolver.providerResolveInfo(it))
        }.orEmpty()
    }

    @Synchronized
    fun queryContentProviders(processName: String?, uid: Int, flags: Int): List<ProviderInfo> {
        ensureRecords()
        val packageName = when {
            uid != 0 && uid != ContainerIdentity.hostUid() -> packageForUid(uid)
            processName != null && records.containsKey(processName) -> processName
            else -> null
        } ?: return emptyList()
        return entry(packageName)?.descriptor?.providers.orEmpty()
    }

    @Synchronized
    fun requestedPermissions(packageName: String?): Set<String> {
        val entry = entry(packageName) ?: return emptySet()
        return entry.packageInfo.requestedPermissions?.toSet().orEmpty()
    }

    @Synchronized
    fun declaredPermissions(packageName: String?): List<PermissionInfo> {
        val entry = entry(packageName) ?: return emptyList()
        return entry.packageInfo.permissions?.toList().orEmpty()
    }

    /** Signature comes from the install record; no APK parse. */
    @Synchronized
    fun signatureOf(packageName: String?): String? =
        record(packageName)?.signatureSha256

    @Synchronized
    fun targetSdkOf(packageName: String?): Int? = entry(packageName)?.descriptor?.targetSdk

    @Synchronized
    fun entryNames(): List<String> = allNames()

    private fun scopedEntry(intent: Intent?): Entry? {
        if (intent == null) return null
        val packageName = intent.component?.packageName ?: intent.`package` ?: return null
        if (packageName == currentPackage) return null
        return entry(packageName)
    }

    /**
     * Provider authorities are conventionally package-prefixed. This check is
     * done against the cheap install index so unrelated system authority
     * lookups do not force every virtual APK to be parsed.
     */
    private fun packageForAuthority(authority: String?): String? {
        if (authority.isNullOrBlank()) return null
        ensureRecords()
        return records.keys.firstOrNull { packageName ->
            authority == packageName ||
                authority.startsWith("$packageName.") ||
                authority.contains(".$packageName.")
        }
    }

    private fun record(packageName: String?): Record? {
        if (packageName == null || packageName == currentPackage) return null
        ensureRecords()
        return records[packageName]
    }

    private fun toMinimalApplicationInfo(record: Record): ApplicationInfo =
        ApplicationInfo().apply {
            packageName = record.packageName
            name = record.packageName
            uid = ContainerIdentity.virtualUidFor(record.packageName)
            sourceDir = record.baseApkPath
            publicSourceDir = record.baseApkPath
            dataDir = dataDirFor(record.packageName, record.instanceId)
            nonLocalizedLabel = record.label
            enabled = true
        }

    private fun toMinimalPackageInfo(record: Record): PackageInfo =
        PackageInfo().apply {
            packageName = record.packageName
            versionName = record.versionName
            versionCode = record.versionCode.toInt()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                longVersionCode = record.versionCode
            }
            applicationInfo = toMinimalApplicationInfo(record)
        }

    private fun dataDirFor(packageName: String, instanceId: String): String =
        File(File(hostContext.filesDir, "vspace/data"), sanitize(packageName))
            .resolve(sanitize(instanceId))
            .absolutePath

    /**
     * Returns the cached descriptor, inspecting the APK on first use.
     * The caller holds the registry monitor.
     */
    private fun entry(packageName: String?): Entry? {
        if (packageName == null || packageName == currentPackage) return null
        ensureRecords()
        entries[packageName]?.let { return it }
        val record = records[packageName] ?: return null
        if (!loading.add(packageName)) return null
        return try {
            val loaded = runCatching { loadEntry(record) }
                .onFailure { Log.w(TAG, "skip virtual package $packageName", it) }
                .getOrNull()
                ?: return null
            entries[packageName] = loaded
            loaded
        } finally {
            loading.remove(packageName)
        }
    }

    @Synchronized
    private fun ensureRecords() {
        if (recordsLoaded) return
        recordsLoaded = true
        runCatching {
            val root = File(hostContext.filesDir, "vspace")
            val store = SpaceStore(File(root, "state.json"))
            store.load()
                .asSequence()
                .filter { it.spaceId == spaceId && it.packageName != currentPackage }
                .forEach { app ->
                    if (File(app.baseApkPath).isFile) {
                        records[app.packageName] = Record(
                            packageName = app.packageName,
                            label = app.label,
                            baseApkPath = app.baseApkPath,
                            splitApkPaths = app.splitApkPaths,
                            launcherActivity = app.launcherActivity,
                            versionName = app.versionName,
                            versionCode = app.versionCode,
                            signatureSha256 = app.signatureSha256,
                            instanceId = app.instances
                                .firstOrNull { it.id == DEFAULT_INSTANCE_ID }
                                ?.id
                                ?: app.instances.minByOrNull { it.createdAt }?.id
                                ?: DEFAULT_INSTANCE_ID
                        )
                    }
                }
        }.onFailure { Log.w(TAG, "load virtual package index failed", it) }
        Log.d(TAG, "indexed ${records.size} virtual packages")
    }

    private fun loadEntry(record: Record): Entry? {
        val descriptor = ApkInspector.inspect(
            hostContext,
            record.baseApkPath,
            requireLauncher = false
        )
        if (descriptor.packageName != record.packageName) return null
        val uid = ContainerIdentity.virtualUidFor(record.packageName)
        val appInfo = descriptor.applicationInfo
        appInfo.uid = uid
        appInfo.sourceDir = record.baseApkPath
        appInfo.publicSourceDir = record.baseApkPath
        appInfo.dataDir = dataDirFor(record.packageName, record.instanceId)

        val resources = createResources(
            listOf(record.baseApkPath) + record.splitApkPaths
        )
        val resolver = ContainerIntentResolver(
            packageName = record.packageName,
            launcherActivity = descriptor.launcherActivity,
            activities = descriptor.activities,
            services = descriptor.services,
            receivers = descriptor.receivers,
            providers = descriptor.providers,
            activityFilters = descriptor.activityFilters,
            serviceFilters = descriptor.serviceFilters,
            receiverFilters = descriptor.receiverFilters,
            contentResolver = contentResolver
        )
        Log.d(
            TAG,
            "loaded ${record.packageName} uid=$uid filters=" +
                "${descriptor.activityFilters.size}/${descriptor.serviceFilters.size}/" +
                descriptor.receiverFilters.size
        )
        return Entry(
            packageName = record.packageName,
            label = record.label.takeIf { it.isNotBlank() } ?: record.packageName,
            descriptor = descriptor,
            resources = resources,
            resolver = resolver,
            uid = uid
        )
    }

    private fun createResources(paths: List<String>): Resources? = runCatching {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        paths.forEach { addAssetPath.invoke(assets, it) }
        Resources(
            assets,
            hostContext.resources.displayMetrics,
            hostContext.resources.configuration
        )
    }.onFailure { Log.w(TAG, "create resources failed", it) }.getOrNull()

    private companion object {
        const val TAG = "VirtualPackageRegistry"
        const val DEFAULT_INSTANCE_ID = "default"

        fun sanitize(value: String): String =
            value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}

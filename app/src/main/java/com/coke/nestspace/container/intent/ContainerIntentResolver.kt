package com.coke.nestspace.container.intent

import android.content.ContentResolver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo

/**
 * Resolves Intents against the components declared by the container APK.
 *
 * Explicit in-package components are handled directly. Implicit Intents are
 * matched against manifest <intent-filter> data parsed by
 * [ManifestIntentFilters], using the platform IntentFilter matcher so MIME
 * types, URI schemes/authorities/paths and categories behave like Android.
 */
class ContainerIntentResolver(
    private val packageName: String,
    private val launcherActivity: String?,
    activities: List<ActivityInfo>,
    services: List<ServiceInfo>,
    receivers: List<ActivityInfo>,
    private val providers: List<ProviderInfo>,
    private val activityFilters: Map<String, List<ContainerIntentFilter>>,
    private val serviceFilters: Map<String, List<ContainerIntentFilter>>,
    private val receiverFilters: Map<String, List<ContainerIntentFilter>>,
    private val contentResolver: () -> ContentResolver?
) {

    val activitiesByName: Map<String, ActivityInfo> = buildMap {
        activities.forEach { info -> put(normalize(info.name), info) }
    }

    val servicesByName: Map<String, ServiceInfo> = buildMap {
        services.forEach { put(normalize(it.name), it) }
    }

    val receiversByName: Map<String, ActivityInfo> = buildMap {
        receivers.forEach { put(normalize(it.name), it) }
    }

    data class ActivityMatch(
        val info: ActivityInfo,
        val filter: ContainerIntentFilter?,
        val match: Int,
        val isDefault: Boolean
    )

    data class ServiceMatch(
        val info: ServiceInfo,
        val filter: ContainerIntentFilter?,
        val match: Int,
        val isDefault: Boolean
    )

    fun findActivity(className: String?): ActivityInfo? =
        activitiesByName[normalize(className)]

    fun findService(className: String?): ServiceInfo? =
        servicesByName[normalize(className)]

    fun findReceiver(className: String?): ActivityInfo? =
        receiversByName[normalize(className)]

    fun resolveActivity(intent: Intent, flags: Int = 0): ActivityMatch? {
        explicitActivity(intent)?.let { return it }
        if (intent.component != null || !packageAllows(intent)) return null
        return queryActivities(intent, flags).firstOrNull()
    }

    fun resolveService(intent: Intent, flags: Int = 0): ServiceMatch? {
        explicitService(intent)?.let { return it }
        if (intent.component != null || !packageAllows(intent)) return null
        return queryServices(intent, flags).firstOrNull()
    }

    fun queryActivities(intent: Intent, flags: Int = 0): List<ActivityMatch> {
        if (intent.component != null) {
            return listOfNotNull(explicitActivity(intent))
        }
        if (!packageAllows(intent)) return emptyList()

        val resolver = contentResolver() ?: return emptyList()
        val matches = mutableListOf<ActivityMatch>()
        activitiesByName.values.distinct().forEach { info ->
            val componentFilters = activityFilters[normalize(info.name)].orEmpty()
            componentFilters.forEach { containerFilter ->
                val platform = runCatching { containerFilter.toPlatform() }.getOrNull()
                    ?: return@forEach
                if (flags and PackageManager.MATCH_DEFAULT_ONLY != 0 &&
                    !platform.hasCategory(Intent.CATEGORY_DEFAULT)
                ) {
                    return@forEach
                }
                val match = runCatching {
                    platform.match(resolver, intent, true, TAG)
                }.getOrDefault(-1)
                if (match >= 0) {
                    matches.add(
                        ActivityMatch(
                            info = info,
                            filter = containerFilter,
                            match = match,
                            isDefault = platform.hasCategory(Intent.CATEGORY_DEFAULT)
                        )
                    )
                }
            }
        }
        return matches.sortedWith(
            compareByDescending<ActivityMatch> { it.filter?.priority ?: 0 }
                .thenByDescending { it.isDefault }
        )
    }

    fun queryServices(intent: Intent, flags: Int = 0): List<ServiceMatch> {
        if (intent.component != null) {
            return listOfNotNull(explicitService(intent))
        }
        if (!packageAllows(intent)) return emptyList()

        val resolver = contentResolver() ?: return emptyList()
        val matches = mutableListOf<ServiceMatch>()
        servicesByName.values.distinct().forEach { info ->
            val componentFilters = serviceFilters[normalize(info.name)].orEmpty()
            componentFilters.forEach { containerFilter ->
                val platform = runCatching { containerFilter.toPlatform() }.getOrNull()
                    ?: return@forEach
                if (flags and PackageManager.MATCH_DEFAULT_ONLY != 0 &&
                    !platform.hasCategory(Intent.CATEGORY_DEFAULT)
                ) {
                    return@forEach
                }
                val match = runCatching {
                    platform.match(resolver, intent, true, TAG)
                }.getOrDefault(-1)
                if (match >= 0) {
                    matches.add(
                        ServiceMatch(
                            info = info,
                            filter = containerFilter,
                            match = match,
                            isDefault = platform.hasCategory(Intent.CATEGORY_DEFAULT)
                        )
                    )
                }
            }
        }
        return matches.sortedWith(
            compareByDescending<ServiceMatch> { it.filter?.priority ?: 0 }
                .thenByDescending { it.isDefault }
        )
    }

    fun queryReceivers(intent: Intent, flags: Int = 0): List<ActivityMatch> {
        val component = intent.component
        if (component != null) {
            if (component.packageName != packageName) return emptyList()
            val info = findReceiver(component.className) ?: return emptyList()
            return listOf(
                ActivityMatch(info, null, IntentFilter.MATCH_CATEGORY_EMPTY, false)
            )
        }
        if (!packageAllows(intent)) return emptyList()

        val resolver = contentResolver() ?: return emptyList()
        val matches = mutableListOf<ActivityMatch>()
        receiversByName.values.distinct().forEach { info ->
            val componentFilters = receiverFilters[normalize(info.name)].orEmpty()
            componentFilters.forEach { containerFilter ->
                val platform = runCatching { containerFilter.toPlatform() }.getOrNull()
                    ?: return@forEach
                if (flags and PackageManager.MATCH_DEFAULT_ONLY != 0 &&
                    !platform.hasCategory(Intent.CATEGORY_DEFAULT)
                ) {
                    return@forEach
                }
                val match = runCatching {
                    platform.match(resolver, intent, true, TAG)
                }.getOrDefault(-1)
                if (match >= 0) {
                    matches.add(
                        ActivityMatch(
                            info = info,
                            filter = containerFilter,
                            match = match,
                            isDefault = platform.hasCategory(Intent.CATEGORY_DEFAULT)
                        )
                    )
                }
            }
        }
        return matches.sortedWith(
            compareByDescending<ActivityMatch> { it.filter?.priority ?: 0 }
                .thenByDescending { it.isDefault }
        )
    }

    fun findProvider(authority: String?): ProviderInfo? {
        if (authority.isNullOrBlank()) return null
        return providers.firstOrNull { info ->
            info.authority
                ?.split(';')
                ?.map { it.trim() }
                ?.any { it == authority } == true
        }
    }

    fun providerMatches(intent: Intent): ProviderInfo? =
        findProvider(intent.data?.authority)

    fun launcherIntent(): Intent? {
        val launcher = activitiesByName.values.firstOrNull { info ->
                activityFilters[normalize(info.name)]?.any { filter ->
                    filter.hasAction(Intent.ACTION_MAIN) &&
                        filter.hasCategory(Intent.CATEGORY_LAUNCHER)
                } == true
            }
            ?: launcherActivity?.let { findActivity(it) }
            ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(packageName, launcher.name)
    }

    fun activityResolveInfo(match: ActivityMatch): ResolveInfo =
        ResolveInfo().apply {
            activityInfo = match.info
            filter = match.filter?.toPlatform()
            priority = match.filter?.priority ?: 0
            preferredOrder = 0
            this.match = match.match
            isDefault = match.isDefault
            resolvePackageName = packageName
            labelRes = match.info.labelRes
            icon = match.info.icon
            nonLocalizedLabel = match.info.nonLocalizedLabel
        }

    fun serviceResolveInfo(match: ServiceMatch): ResolveInfo =
        ResolveInfo().apply {
            serviceInfo = match.info
            filter = match.filter?.toPlatform()
            priority = match.filter?.priority ?: 0
            preferredOrder = 0
            this.match = match.match
            isDefault = match.isDefault
            resolvePackageName = packageName
            labelRes = match.info.labelRes
            icon = match.info.icon
            nonLocalizedLabel = match.info.nonLocalizedLabel
        }

    fun providerResolveInfo(info: ProviderInfo): ResolveInfo =
        ResolveInfo().apply {
            providerInfo = info
            resolvePackageName = packageName
            icon = info.icon
            nonLocalizedLabel = info.nonLocalizedLabel
        }

    private fun explicitActivity(intent: Intent): ActivityMatch? {
        val component = intent.component ?: return null
        if (component.packageName != packageName) return null
        val info = findActivity(component.className) ?: return null
        return ActivityMatch(info, null, IntentFilter.MATCH_CATEGORY_EMPTY, false)
    }

    private fun explicitService(intent: Intent): ServiceMatch? {
        val component = intent.component ?: return null
        if (component.packageName != packageName) return null
        val info = findService(component.className) ?: return null
        return ServiceMatch(info, null, IntentFilter.MATCH_CATEGORY_EMPTY, false)
    }

    private fun packageAllows(intent: Intent): Boolean {
        val pkg = intent.`package` ?: return true
        return pkg == packageName
    }

    fun normalize(name: String?): String {
        val value = name.orEmpty()
        return when {
            value.startsWith(".") -> packageName + value
            value.isNotEmpty() && !value.contains('.') -> "$packageName.$value"
            else -> value
        }
    }

    private companion object {
        const val TAG = "ContainerIntent"
    }
}

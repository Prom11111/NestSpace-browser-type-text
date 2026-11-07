package com.coke.nestspace.container.intent

import android.content.res.AssetManager
import android.content.res.XmlResourceParser
import android.util.Log
import com.coke.nestspace.container.HiddenApiCompat

/**
 * Reads <intent-filter> declarations from an APK's compiled AndroidManifest.
 *
 * Uses AssetManager's XML parser (the APK is not installed, so
 * PackageManager.getPackageArchiveInfo does not expose intent filters). The
 * parser is hidden API, hence HiddenApiCompat must be active; that is already
 * installed for every container launch and is made idempotent here.
 */
object ManifestIntentFilters {

    private const val TAG = "ManifestFilters"
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    data class Parsed(
        val packageName: String,
        val activities: Map<String, List<ContainerIntentFilter>>,
        val activityAliases: Map<String, String>,
        val services: Map<String, List<ContainerIntentFilter>>,
        val receivers: Map<String, List<ContainerIntentFilter>>
    )

    fun parse(apkPath: String): Parsed {
        Log.d(TAG, "parse start: $apkPath")
        HiddenApiCompat.ensure()
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val parser: XmlResourceParser
        try {
            val cookie = AssetManager::class.java
                .getMethod("addAssetPath", String::class.java)
                .invoke(assets, apkPath) as? Int ?: 0
            if (cookie == 0) {
                Log.w(TAG, "addAssetPath failed: $apkPath")
                return empty(apkPath)
            }
            parser = assets.openXmlResourceParser(cookie, "AndroidManifest.xml")
        } catch (t: Throwable) {
            Log.w(TAG, "open manifest failed: $apkPath", t)
            assets.close()
            return empty(apkPath)
        }

        val activities = linkedMapOf<String, MutableList<ContainerIntentFilter>>()
        val activityAliases = linkedMapOf<String, String>()
        val services = linkedMapOf<String, MutableList<ContainerIntentFilter>>()
        val receivers = linkedMapOf<String, MutableList<ContainerIntentFilter>>()
        var packageName = ""
        var inApplication = false
        var componentName: String? = null
        var componentMap: MutableMap<String, MutableList<ContainerIntentFilter>>? = null
        var activityAliasTarget: String? = null
        var current: ContainerIntentFilter? = null

        try {
            parser.use { xml ->
                var event = xml.eventType
                while (event != XmlResourceParser.END_DOCUMENT) {
                    if (event == XmlResourceParser.START_TAG) {
                        when (xml.name) {
                            "manifest" -> {
                                packageName = attr(xml, "package").orEmpty()
                            }
                            "application" -> inApplication = true
                            "activity", "activity-alias" -> if (inApplication) {
                                componentMap = activities
                                componentName = normalize(packageName, attr(xml, "name"))
                                activityAliasTarget = if (xml.name == "activity-alias") {
                                    normalize(packageName, attr(xml, "targetActivity"))
                                } else {
                                    null
                                }
                            }
                            "service" -> if (inApplication) {
                                componentMap = services
                                componentName = normalize(packageName, attr(xml, "name"))
                            }
                            "receiver" -> if (inApplication) {
                                componentMap = receivers
                                componentName = normalize(packageName, attr(xml, "name"))
                            }
                            "intent-filter" -> if (componentName != null) {
                                current = ContainerIntentFilter().apply {
                                    priority = xml.getAttributeIntValue(
                                        ANDROID_NS,
                                        "priority",
                                        0
                                    )
                                }
                            }
                            "action" -> if (current != null) {
                                attr(xml, "name")?.takeIf { it.isNotBlank() }
                                    ?.let { current?.actions?.add(it) }
                            }
                            "category" -> if (current != null) {
                                attr(xml, "name")?.takeIf { it.isNotBlank() }
                                    ?.let { current?.categories?.add(it) }
                            }
                            "data" -> if (current != null) {
                                addData(xml, current!!)
                            }
                        }
                    } else if (event == XmlResourceParser.END_TAG) {
                        when (xml.name) {
                            "intent-filter" -> {
                                val filter = current
                                val name = componentName
                                val map = componentMap
                                if (filter != null && name != null && map != null) {
                                    map.getOrPut(name) { mutableListOf() }.add(filter)
                                }
                                current = null
                            }
                            "activity-alias" -> {
                                val alias = componentName
                                val target = activityAliasTarget
                                if (alias != null && target != null) {
                                    activityAliases[alias] = target
                                }
                                componentName = null
                                componentMap = null
                                activityAliasTarget = null
                            }
                            "activity", "service", "receiver" -> {
                                componentName = null
                                componentMap = null
                            }
                            "application" -> inApplication = false
                        }
                    }
                    event = xml.next()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parse manifest failed: $apkPath", t)
        } finally {
            runCatching { assets.close() }
        }

        return Parsed(
            packageName = packageName,
            activities = activities,
            activityAliases = activityAliases,
            services = services,
            receivers = receivers
        ).also {
            Log.d(
                TAG,
                "parse done: pkg=$packageName activities=${activities.size} " +
                    "aliases=${activityAliases.size} services=${services.size} " +
                    "receivers=${receivers.size}"
            )
        }
    }

    private fun addData(xml: XmlResourceParser, filter: ContainerIntentFilter) {
        attr(xml, "scheme")?.takeIf { it.isNotBlank() }?.let { filter.schemes.add(it) }
        attr(xml, "mimeType")?.takeIf { it.isNotBlank() }?.let { filter.mimeTypes.add(it) }

        val host = attr(xml, "host")?.takeIf { it.isNotBlank() }
        val port = attr(xml, "port")?.takeIf { it.isNotBlank() }
        if (host != null) {
            filter.authorities.add(ContainerIntentFilter.Authority(host, port))
        }

        attr(xml, "path")?.let {
            filter.paths.add(
                ContainerIntentFilter.PathSpec(ContainerIntentFilter.PathKind.LITERAL, it)
            )
        }
        attr(xml, "pathPrefix")?.let {
            filter.paths.add(
                ContainerIntentFilter.PathSpec(ContainerIntentFilter.PathKind.PREFIX, it)
            )
        }
        attr(xml, "pathPattern")?.let {
            filter.paths.add(
                ContainerIntentFilter.PathSpec(ContainerIntentFilter.PathKind.SIMPLE_GLOB, it)
            )
        }
        attr(xml, "pathAdvancedPattern")?.let {
            filter.paths.add(
                ContainerIntentFilter.PathSpec(ContainerIntentFilter.PathKind.ADVANCED_GLOB, it)
            )
        }
        attr(xml, "pathSuffix")?.let {
            filter.paths.add(
                ContainerIntentFilter.PathSpec(
                    ContainerIntentFilter.PathKind.SIMPLE_GLOB,
                    "*$it"
                )
            )
        }

        attr(xml, "ssp")?.let {
            filter.schemeSpecificParts.add(
                ContainerIntentFilter.SspSpec(ContainerIntentFilter.SspKind.LITERAL, it)
            )
        }
        attr(xml, "sspPrefix")?.let {
            filter.schemeSpecificParts.add(
                ContainerIntentFilter.SspSpec(ContainerIntentFilter.SspKind.PREFIX, it)
            )
        }
        attr(xml, "sspSuffix")?.let {
            filter.schemeSpecificParts.add(
                ContainerIntentFilter.SspSpec(
                    ContainerIntentFilter.SspKind.SIMPLE_GLOB,
                    "*$it"
                )
            )
        }
        attr(xml, "sspPattern")?.let {
            filter.schemeSpecificParts.add(
                ContainerIntentFilter.SspSpec(
                    ContainerIntentFilter.SspKind.SIMPLE_GLOB,
                    it
                )
            )
        }
        attr(xml, "sspAdvancedPattern")?.let {
            filter.schemeSpecificParts.add(
                ContainerIntentFilter.SspSpec(
                    ContainerIntentFilter.SspKind.ADVANCED_GLOB,
                    it
                )
            )
        }
    }

    private fun attr(xml: XmlResourceParser, name: String): String? =
        xml.getAttributeValue(ANDROID_NS, name)
            ?: xml.getAttributeValue(null, name)

    private fun normalize(packageName: String, raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when {
            value.startsWith(".") -> packageName + value
            !value.contains('.') -> "$packageName.$value"
            else -> value
        }
    }

    private fun empty(apkPath: String): Parsed {
        Log.w(TAG, "no manifest filters available for $apkPath")
        return Parsed("", emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }
}

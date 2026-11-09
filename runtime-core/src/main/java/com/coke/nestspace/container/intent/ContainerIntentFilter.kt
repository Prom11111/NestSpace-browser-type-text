package com.coke.nestspace.container.intent

import android.content.IntentFilter
import android.os.PatternMatcher

/**
 * Manifest intent-filter data for a component that lives inside a container
 * APK. The platform PackageManager cannot see these filters because the APK is
 * not installed on the host, so we parse them from the APK manifest and use
 * them for in-container implicit Intent resolution.
 *
 * A single <intent-filter> element is represented by one instance; multiple
 * <data> children are merged into the same filter, matching Android's own
 * manifest parser behavior.
 */
class ContainerIntentFilter {

    val actions = linkedSetOf<String>()
    val categories = linkedSetOf<String>()
    val schemes = linkedSetOf<String>()
    val mimeTypes = linkedSetOf<String>()
    val authorities = linkedSetOf<Authority>()
    val paths = linkedSetOf<PathSpec>()
    val schemeSpecificParts = linkedSetOf<SspSpec>()

    var priority: Int = 0

    fun toPlatform(): IntentFilter = IntentFilter().apply {
        actions.forEach { addAction(it) }
        categories.forEach { addCategory(it) }
        schemes.forEach { addDataScheme(it) }
        mimeTypes.forEach { addDataType(it) }
        authorities.forEach { addDataAuthority(it.host, it.port) }
        paths.forEach {
            addDataPath(
                it.value,
                when (it.kind) {
                    PathKind.LITERAL -> PatternMatcher.PATTERN_LITERAL
                    PathKind.PREFIX -> PatternMatcher.PATTERN_PREFIX
                    PathKind.SIMPLE_GLOB -> PatternMatcher.PATTERN_SIMPLE_GLOB
                    PathKind.ADVANCED_GLOB -> PatternMatcher.PATTERN_ADVANCED_GLOB
                }
            )
        }
        schemeSpecificParts.forEach {
            addDataSchemeSpecificPart(
                it.value,
                when (it.kind) {
                    SspKind.LITERAL -> PatternMatcher.PATTERN_LITERAL
                    SspKind.PREFIX -> PatternMatcher.PATTERN_PREFIX
                    SspKind.SIMPLE_GLOB -> PatternMatcher.PATTERN_SIMPLE_GLOB
                    SspKind.ADVANCED_GLOB -> PatternMatcher.PATTERN_ADVANCED_GLOB
                }
            )
        }
        priority = this@ContainerIntentFilter.priority
    }

    fun hasAction(action: String?): Boolean = action != null && actions.contains(action)

    fun hasCategory(category: String): Boolean = categories.contains(category)

    data class Authority(val host: String, val port: String?)

    data class PathSpec(val kind: PathKind, val value: String)

    data class SspSpec(val kind: SspKind, val value: String)

    enum class PathKind { LITERAL, PREFIX, SIMPLE_GLOB, ADVANCED_GLOB }

    enum class SspKind { LITERAL, PREFIX, SIMPLE_GLOB, ADVANCED_GLOB }
}

package com.coke.nestspace.ui.desktop

import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import com.coke.nestspace.R
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.data.VirtualApp

internal enum class LauncherKind { VIRTUAL, SYSTEM, BROWSER, AGENT, SPACES, RUNNING }

internal data class LauncherEntry(
    val id: String,
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val kind: LauncherKind,
    val vectorIcon: ImageVector? = null,
    val drawableRes: Int? = null
)

internal sealed interface DesktopSlot {
    val id: String

    data class App(val entry: LauncherEntry) : DesktopSlot {
        override val id: String get() = entry.id
    }

    data class Folder(
        override val id: String,
        val name: String,
        val apps: List<LauncherEntry>
    ) : DesktopSlot
}

internal fun buildEntries(
    virtualApps: List<VirtualApp>,
    systemApps: List<com.coke.nestspace.data.AppInfo>
): List<LauncherEntry> {
    val virtualPackages = virtualApps.map { it.packageName }.toSet()
    return virtualApps.sortedBy { it.label.lowercase() }.map {
        LauncherEntry(
            id = "virtual:${it.packageName}",
            packageName = it.packageName,
            label = it.label,
            icon = it.icon,
            kind = LauncherKind.VIRTUAL
        )
    } + systemApps
        .filterNot { it.packageName in virtualPackages }
        .sortedBy { it.label.lowercase() }
        .map {
            LauncherEntry(
                id = "system:${it.packageName}",
                packageName = it.packageName,
                label = it.label,
                icon = it.icon,
                kind = LauncherKind.SYSTEM
            )
        } + listOf(
        LauncherEntry(
            id = "action:browser",
            packageName = "action:browser",
            label = AppLanguage.tr("浏览器", "Browser"),
            icon = null,
            kind = LauncherKind.BROWSER,
            vectorIcon = Icons.Default.Public
        ),
        LauncherEntry(
            id = "action:agent",
            packageName = "action:agent",
            label = AppLanguage.tr("AI助手", "AI assistant"),
            icon = null,
            kind = LauncherKind.AGENT,
            drawableRes = R.drawable.ai_assistant
        ),
        LauncherEntry(
            id = "action:spaces",
            packageName = "action:spaces",
            label = AppLanguage.tr("空间设置", "Space settings"),
            icon = null,
            kind = LauncherKind.SPACES,
            vectorIcon = Icons.Default.Settings
        ),
        LauncherEntry(
            id = "action:running",
            packageName = "action:running",
            label = AppLanguage.tr("运行管理", "Running apps"),
            icon = null,
            kind = LauncherKind.RUNNING,
            vectorIcon = Icons.Default.Memory
        )
    )
}

internal fun resolveSlots(
    saved: List<SavedDesktopSlot>,
    entries: List<LauncherEntry>,
    keepSingleApp: Set<String> = emptySet()
): List<DesktopSlot> {
    val byId = entries.associateBy { it.id }
    val used = HashSet<String>()
    val result = ArrayList<DesktopSlot>()
    saved.forEach { slot ->
        when (slot) {
            is SavedDesktopSlot.App -> byId[slot.appId]?.let {
                if (used.add(it.id)) result += DesktopSlot.App(it)
            }

            is SavedDesktopSlot.Folder -> {
                val apps = slot.appIds.mapNotNull { byId[it] }
                    .filter { used.add(it.id) }
                // A one-app folder is dissolved, except while the user is
                // filling a folder they just created. Intentionally empty new
                // folders are kept, while folders whose apps all disappeared
                // are dropped.
                when {
                    apps.size == 1 && slot.id !in keepSingleApp ->
                        result += DesktopSlot.App(apps.first())
                    slot.appIds.isEmpty() -> result += DesktopSlot.Folder(slot.id, slot.name, apps)
                    apps.isNotEmpty() -> result += DesktopSlot.Folder(slot.id, slot.name, apps)
                }
            }
        }
    }
    entries.forEach { entry ->
        if (used.add(entry.id)) result += DesktopSlot.App(entry)
    }
    return result
}

internal fun DesktopSlot.toSaved(): SavedDesktopSlot = when (this) {
    is DesktopSlot.App -> SavedDesktopSlot.App(entry.id)
    is DesktopSlot.Folder -> SavedDesktopSlot.Folder(id, name, apps.map { it.id })
}

internal fun moveSlot(slots: List<DesktopSlot>, from: Int, to: Int): List<DesktopSlot> {
    if (from == to || from !in slots.indices || to !in slots.indices) return slots
    val next = slots.toMutableList()
    val item = next.removeAt(from)
    next.add(to.coerceIn(0, next.size), item)
    return next
}

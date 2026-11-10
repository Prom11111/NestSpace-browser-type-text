package com.coke.nestspace.data

import android.os.Bundle
import androidx.compose.ui.graphics.ImageBitmap

enum class SpaceType { SYSTEM, VIRTUAL }

data class SpaceInfo(
    val id: String,
    val name: String,
    val type: SpaceType,
    val status: String,
    val appCount: Int = 0
)

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val isSystem: Boolean,
    val apkPaths: List<String> = emptyList()
)

data class VirtualInstance(
    val id: String,
    val createdAt: Long,
    val lastLaunchedAt: Long,
    val dataDir: String,
    val environmentVersion: Int = 1,
    val lastProbeResult: String? = null
)

/** One static Android app shortcut parsed from the APK's shortcut XML. */
data class AppShortcut(
    val id: String,
    val label: String,
    val icon: ImageBitmap?,
    val targetActivity: String,
    val action: String?,
    val data: String?,
    val mimeType: String?,
    val categories: List<String>,
    val extras: Bundle,
    val intentUri: String? = null
)

data class VirtualApp(
    val spaceId: String,
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val launcherActivity: String,
    val signatureSha256: String,
    val hasNativeLibs: Boolean,
    val installedAt: Long,
    val updatedAt: Long,
    val instances: List<VirtualInstance>,
    val icon: ImageBitmap? = null
)

data class LaunchInfo(
    val spaceId: String,
    val packageName: String,
    val instanceId: String,
    val activity: String,
    val dataDir: String,
    val targetProcess: String? = null,
    val slot: Int? = null,
    /** The previous slot process died; this launch starts a fresh one. */
    val restartedAfterCrash: Boolean = false,
    /** Human-readable reason when [restartedAfterCrash] is true. */
    val crashReason: String? = null,
    /** The target was started in its host-installed package as a compatibility fallback. */
    val hostFallback: Boolean = false,
    val fallbackReason: String? = null,
    val runtimePluginId: String? = null,
    val runtimePolicyId: String? = null
)

data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class UiNode(
    val id: String,
    val role: String,
    val text: String?,
    val desc: String?,
    val bounds: Bounds,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val selected: Boolean
)

data class UiSnapshot(
    val packageName: String,
    val activity: String,
    val nodes: List<UiNode>,
    val screenWidth: Int,
    val screenHeight: Int,
    val timestamp: Long
)

data class AgentMessage(
    val role: Role,
    val text: String,
    /** Where this turn originated; MCP turns are shown in the MCP task center. */
    val origin: Origin = Origin.LOCAL,
    /** True only for the localized greeting that the assistant UI replaces with WelcomeState. */
    val welcome: Boolean = false,
    /** A pending procedure memory that can be confirmed or ignored in the chat. */
    val pendingMemoryId: String? = null,
    /** approved/ignored once the inline memory card has been resolved. */
    val memoryDecision: String? = null,
    /** A newly saved skill that can be enabled or ignored in the chat. */
    val pendingSkillId: String? = null,
    val skillDecision: String? = null
) {
    enum class Role { USER, AGENT, TOOL, ERROR }
    enum class Origin { LOCAL, MCP, SCHEDULED }
}

data class McpStatus(
    val running: Boolean = false,
    val address: String = "",
    val token: String = "",
    val error: String? = null,
    val lanEnabled: Boolean = false,
    val localAddress: String = "",
    val lanAddress: String = ""
)

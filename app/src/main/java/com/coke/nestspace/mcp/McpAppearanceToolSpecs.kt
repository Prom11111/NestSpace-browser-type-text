package com.coke.nestspace.mcp

import android.content.Context
import android.util.Base64
import com.coke.nestspace.assistant.FloatingDotOverlay
import com.coke.nestspace.assistant.FloatingDotSettings
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.data.DesktopAppsStore
import com.coke.nestspace.data.WallpaperStore
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.i18n.AppLanguageMode
import com.coke.nestspace.tools.ToolResult
import com.coke.nestspace.tools.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal fun mcpAppearanceToolSpecs(context: Context): List<ToolSpec> {
    val app = context.applicationContext
    return listOf(
            ToolSpec(
                name = "nestspace_language_set",
                description = "Set the host UI language mode: system, zh or en.",
                inputSchema = objectSchema(
                    "mode" to stringProp("system, zh or en"),
                    required = listOf("mode")
                )
            ) { args, _ ->
                val mode = when (args.optString("mode").trim().lowercase()) {
                    "system", "auto", "follow" -> AppLanguageMode.SYSTEM
                    "zh", "chinese", "中文" -> AppLanguageMode.CHINESE
                    "en", "english" -> AppLanguageMode.ENGLISH
                    else -> return@ToolSpec invalid("mode must be system, zh or en")
                }
                AppLanguage.setMode(app, mode)
                AppSettingsEvents.notifyChanged()
                ToolResult(
                    true,
                    JSONObject()
                        .put("mode", mode.storageValue)
                        .put("effective", if (AppLanguage.isChinese()) "zh" else "en")
                        .toString()
                )
            },

            ToolSpec(
                name = "nestspace_slots_set",
                description = "Set the container slot pool. mode=auto uses the full pool with " +
                    "memory-pressure eviction; mode=fixed requires limit (1-16).",
                inputSchema = objectSchema(
                    "mode" to stringProp("auto or fixed"),
                    "limit" to intProp("fixed slot count, 1-16")
                )
            ) { args, _ ->
                val mode = args.optString("mode").trim().lowercase()
                val limit = if (args.has("limit")) args.optInt("limit") else null
                when (mode) {
                    "auto" -> ContainerProcessPool.applySlotLimit(
                        app,
                        ContainerProcessPool.AUTO_SLOT_LIMIT
                    )

                    "fixed" -> {
                        if (limit == null || limit !in 1..ContainerProcessPool.SLOT_COUNT) {
                            return@ToolSpec invalid(
                                "fixed mode needs limit between 1 and " +
                                    ContainerProcessPool.SLOT_COUNT
                            )
                        }
                        ContainerProcessPool.applySlotLimit(app, limit)
                    }

                    "" -> {
                        if (limit == null) return@ToolSpec invalid("mode or limit is required")
                        ContainerProcessPool.applySlotLimit(
                            app,
                            if (limit == ContainerProcessPool.AUTO_SLOT_LIMIT) limit
                            else limit.coerceIn(1, ContainerProcessPool.SLOT_COUNT)
                        )
                    }

                    else -> return@ToolSpec invalid("mode must be auto or fixed")
                }
                AppSettingsEvents.notifyChanged()
                ToolResult(true, slotsJson(app).toString())
            },

            ToolSpec(
                name = "nestspace_floating_dot_set",
                description = "Configure the floating dot. Colors accept #RRGGBB or " +
                    "#AARRGGBB. speech_language is zh or en. opacity is 10-100, where " +
                    "100 is fully opaque. Omitted fields are unchanged.",
                inputSchema = objectSchema(
                    "enabled" to boolProp("show the floating dot"),
                    "host_color" to stringProp("#RRGGBB over NestSpace"),
                    "container_color" to stringProp("#RRGGBB over virtual apps"),
                    "speech_language" to stringProp("zh or en"),
                    "opacity" to intProp("10-100; 100 is fully opaque"),
                    "x" to intProp("saved screen x"),
                    "y" to intProp("saved screen y")
                )
            ) { args, _ ->
                val current = FloatingDotSettings.load(app)
                val hostColor = if (args.has("host_color")) {
                    parseColor(args.optString("host_color"))
                        ?: return@ToolSpec invalid("host_color must be #RRGGBB or #AARRGGBB")
                } else {
                    current.hostColor
                }
                val containerColor = if (args.has("container_color")) {
                    parseColor(args.optString("container_color"))
                        ?: return@ToolSpec invalid("container_color must be #RRGGBB or #AARRGGBB")
                } else {
                    current.containerColor
                }
                val speech = if (args.has("speech_language")) {
                    args.optString("speech_language").trim().lowercase().also {
                        if (it != FloatingDotSettings.SPEECH_LANG_ZH &&
                            it != FloatingDotSettings.SPEECH_LANG_EN
                        ) {
                            return@ToolSpec invalid("speech_language must be zh or en")
                        }
                    }
                } else {
                    current.speechLanguage
                }
                val opacity = if (args.has("opacity")) {
                    args.optInt("opacity").also {
                        if (it !in FloatingDotSettings.MIN_OPACITY..
                            FloatingDotSettings.MAX_OPACITY
                        ) {
                            return@ToolSpec invalid(
                                "opacity must be between ${FloatingDotSettings.MIN_OPACITY} " +
                                    "and ${FloatingDotSettings.MAX_OPACITY}"
                            )
                        }
                    }
                } else {
                    current.opacity
                }
                FloatingDotSettings.save(
                    app,
                    current.copy(
                        enabled = if (args.has("enabled")) args.optBoolean("enabled")
                        else current.enabled,
                        hostColor = hostColor,
                        containerColor = containerColor,
                        x = if (args.has("x")) args.optInt("x") else current.x,
                        y = if (args.has("y")) args.optInt("y") else current.y,
                        speechLanguage = speech,
                        opacity = opacity
                    )
                )
                AppSettingsEvents.notifyChanged()
                withContext(Dispatchers.Main.immediate) { FloatingDotOverlay.reload(app) }
                ToolResult(true, floatingDotJson(app).toString())
            },

            ToolSpec(
                name = "nestspace_desktop_apps",
                description = "Read or change system-app shortcuts on the desktop. Actions: " +
                    "get, set, add, remove. apps is a JSON array of package names.",
                inputSchema = objectSchema(
                    "action" to stringProp("get, set, add or remove"),
                    "apps" to arrayProp("array of package names")
                )
            ) { args, _ ->
                val action = args.optString("action", "get").trim().lowercase()
                val requested = stringList(args.optJSONArray("apps"))
                val current = DesktopAppsStore.load(app)
                val next = when (action) {
                    "get" -> current
                    "set" -> requested.toSet()
                    "add" -> current + requested
                    "remove" -> current - requested.toSet()
                    else -> return@ToolSpec invalid("action must be get, set, add or remove")
                }
                if (action != "get") {
                    val invalidPackages = next.filter { !isLaunchable(app, it) }
                    if (invalidPackages.isNotEmpty()) {
                        return@ToolSpec ToolResult(
                            false,
                            "not launchable: ${invalidPackages.joinToString()}",
                            errorCode = "INVALID_PACKAGE"
                        )
                    }
                    DesktopAppsStore.save(app, next)
                    AppSettingsEvents.notifyChanged()
                }
                ToolResult(
                    true,
                    JSONObject()
                        .put("apps", JSONArray(next.sorted()))
                        .put("count", next.size)
                        .toString()
                )
            },

            ToolSpec(
                name = "nestspace_wallpaper",
                description = "Get, set or clear the desktop wallpaper. set accepts a base64 " +
                    "image in image_base64 (data:image/...;base64, prefix is allowed).",
                inputSchema = objectSchema(
                    "action" to stringProp("get, set or clear"),
                    "image_base64" to stringProp("base64 image data")
                )
            ) { args, _ ->
                val store = WallpaperStore(app)
                when (args.optString("action", "get").trim().lowercase()) {
                    "get" -> ToolResult(
                        true,
                        JSONObject().put("wallpaper_set", store.exists()).toString()
                    )

                    "clear" -> {
                        store.clear()
                        AppSettingsEvents.notifyChanged()
                        ToolResult(true, JSONObject().put("wallpaper_set", false).toString())
                    }

                    "set" -> {
                        val encoded = args.optString("image_base64").trim()
                            .substringAfter("base64,", args.optString("image_base64").trim())
                        val bytes = runCatching {
                            Base64.decode(encoded, Base64.DEFAULT)
                        }.getOrNull()
                        if (bytes == null || bytes.isEmpty()) {
                            return@ToolSpec invalid("image_base64 is not valid base64")
                        }
                        if (bytes.size > MAX_WALLPAPER_BYTES) {
                            return@ToolSpec ToolResult(
                                false,
                                "image is larger than ${MAX_WALLPAPER_BYTES / 1024 / 1024} MB",
                                errorCode = "IMAGE_TOO_LARGE"
                            )
                        }
                        if (!store.saveBytes(bytes)) {
                            return@ToolSpec ToolResult(
                                false,
                                "image could not be decoded",
                                errorCode = "INVALID_IMAGE"
                            )
                        }
                        AppSettingsEvents.notifyChanged()
                        ToolResult(true, JSONObject().put("wallpaper_set", true).toString())
                    }

                    else -> invalid("action must be get, set or clear")
                }
            },
    )
}

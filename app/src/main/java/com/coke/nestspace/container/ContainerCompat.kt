package com.coke.nestspace.container

import android.app.Application

/**
 * Narrow compatibility profiles for hardened APKs.
 *
 * Keep profile-specific workarounds here instead of spreading package names
 * and class names through the container core.
 */
internal object ContainerCompat {

    const val NET_EASE_PACKAGE = "com.netease.cloudmusic"
    const val NET_EASE_MAIN_ACTIVITY = "com.netease.cloudmusic.activity.MainActivity"
    const val NET_EASE_APPLICATION_LIKE =
        "com.netease.cloudmusic.CloudMusicApplicationLike"
    const val NET_EASE_PATCH_APPLICATION_LIKE =
        "com.netease.cloudmusic.core.patch.PatchApplicationLike"
    const val NET_EASE_REAL_APPLICATION =
        "com.netease.cloudmusic.core.patch.RealApplication"

    private val appLikes = java.util.WeakHashMap<Application, Any>()

    fun isNetEase(packageName: String): Boolean = packageName == NET_EASE_PACKAGE

    /**
     * libnesdk is an optional NetEase SDK whose background thread SIGSEGVs in
     * this process after startup. The app catches the missing-library path and
     * keeps its UI/audio stack working, so keep it out of the extracted dir.
     */
    fun excludedNativeLibs(packageName: String): Set<String> = when {
        isNetEase(packageName) -> setOf("libnesdk.so")
        else -> emptySet()
    }

    /** A manifest MAIN/LAUNCHER override for packages with a debug launcher. */
    fun launcherOverride(packageName: String): String? = when {
        isNetEase(packageName) -> NET_EASE_MAIN_ACTIVITY
        else -> null
    }

    /** Keep the Tinker ApplicationLike until the session context is wired. */
    fun rememberAppLike(application: Application, like: Any) {
        synchronized(appLikes) { appLikes[application] = like }
    }

    fun takeAppLike(application: Application): Any? =
        synchronized(appLikes) { appLikes.remove(application) }
}

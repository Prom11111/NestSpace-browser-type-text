package com.coke.nestspace.container.loader

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Target APKs may declare <uses-library> entries such as
 * `org.apache.http.legacy`. The platform normally adds those shared-library
 * jars to the app's class loader at install time. A container APK is never
 * installed, so its ClassLoader must splice the declared libraries in
 * explicitly or libraries that use Apache HTTP fail with NoClassDefFoundError.
 *
 * Built-in shared libraries are published by the platform as
 * `/system/framework/<name>.jar` (see the platform permission XML files);
 * that is enough for the libraries a normal APK can declare.
 */
internal object SharedLibraries {

    private const val TAG = "ContainerSharedLibs"
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private val systemFrameworkDirs = arrayOf(
        "/system/framework",
        "/system_ext/framework",
        "/product/framework",
        "/vendor/framework"
    )

    fun classPathEntries(context: Context, apkPaths: List<String>): List<String> {
        val names = runCatching { readNames(apkPaths) }
            .onFailure { Log.w(TAG, "read uses-library failed", it) }
            .getOrDefault(emptyList())
        if (names.isEmpty()) return emptyList()

        return names.mapNotNull { name ->
            val path = resolve(name)
            if (path != null) {
                Log.d(TAG, "shared library $name -> $path")
            } else {
                Log.w(TAG, "shared library not found: $name")
            }
            path
        }.distinct()
    }

    private fun readNames(apkPaths: List<String>): List<String> {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java
            .getMethod("addAssetPath", String::class.java)
        apkPaths.forEach { path -> addAssetPath.invoke(assets, path) }

        val parser = assets.openXmlResourceParser("AndroidManifest.xml")
        val names = mutableListOf<String>()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "uses-library") {
                    parser.getAttributeValue(ANDROID_NS, "name")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(names::add)
                }
                event = parser.next()
            }
        } finally {
            runCatching { parser.close() }
        }
        return names
    }

    private fun resolve(name: String): String? {
        if (name.contains('/') || name.contains("..")) return null
        return systemFrameworkDirs
            .asSequence()
            .map { File(it, "$name.jar") }
            .firstOrNull { it.isFile }
            ?.absolutePath
    }
}

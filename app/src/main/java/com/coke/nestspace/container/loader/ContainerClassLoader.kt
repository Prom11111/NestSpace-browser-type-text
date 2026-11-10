package com.coke.nestspace.container.loader

import dalvik.system.PathClassLoader

/**
 * Child-first class loader for a virtual app.
 *
 * A normal PathClassLoader delegates to its parent first. Because NestSpace
 * itself bundles AndroidX, a target APK that also bundles AndroidX (very
 * common in real apps) would silently link against the host's version. That
 * breaks apps whose bundled version has different members (AMap's
 * ViewCompat.a is one example).
 *
 * PathClassLoader (rather than DexClassLoader) is deliberate: split APK
 * frameworks such as Taobao's InjectClassLoader downcast the application
 * class loader to PathClassLoader to append split dex paths. Both loaders are
 * BaseDexClassLoader variants; PathClassLoader just matches what installed
 * apps expose.
 *
 * Try the target dex files first, then fall back to the host/system loader.
 */
class ContainerClassLoader(
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader
) : PathClassLoader(dexPath, librarySearchPath, parent) {

    private val lock = Any()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(lock) {
            var clazz = findLoadedClass(name)
            if (clazz == null) {
                // Some SDKs bundle compile-time stubs of framework classes
                // (AMap bundles dalvik.system.PathClassLoader in its APK).
                // Loading those instead of the real boot classes makes the SDK
                // throw RuntimeException("Stub!"). Framework namespaces always
                // come from the parent/boot loader.
                clazz = if (isPlatformClass(name)) {
                    super.loadClass(name, false)
                } else {
                    val target = runCatching { findClass(name) }.getOrNull()
                    if (target != null) {
                        loadedClasses.add(name)
                        target
                    } else {
                        super.loadClass(name, false)
                    }
                }
            }
            if (resolve) resolveClass(clazz)
            return clazz
        }
    }

    private fun isPlatformClass(name: String): Boolean =
        name.startsWith("java.") ||
            name.startsWith("javax.") ||
            name.startsWith("dalvik.") ||
            name.startsWith("android.") ||
            name.startsWith("libcore.") ||
            name.startsWith("sun.") ||
            name.startsWith("com.sun.") ||
            name.startsWith("org.w3c.") ||
            name.startsWith("org.xmlpull.")

    companion object {
        /**
         * Names of classes actually loaded from a target APK. Target-created
         * threads often keep the host context ClassLoader, so crash guard
         * checks the stack trace against this set instead of the thread.
         */
        val loadedClasses: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}

/**
 * Native libraries (notably AMap's AmapkitLoaderBridge) capture
 * Thread.getContextClassLoader() during JNI initialization and use it to find
 * target-side classes. Target code executes on the host main thread, whose
 * context loader is NestSpace's own loader; without this swap the bridge sees
 * the host loader and disables target native modules.
 */
inline fun <T> withContainerClassLoader(loader: ClassLoader, block: () -> T): T {
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    thread.contextClassLoader = loader
    return try {
        block()
    } finally {
        thread.contextClassLoader = previous
    }
}

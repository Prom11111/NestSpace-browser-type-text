package com.coke.nestspace.container.compat

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.coke.nestspace.container.loader.ApkClassIndex
import com.coke.nestspace.runtime.RuntimeApplicationSpec
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

/**
 * Resolves an APK's effective process Application without binding the
 * container core to individual app package names.
 *
 * NIS wrappers are identified by the packer namespace, then the app's own DEX
 * is searched for the real Application. Tinker wrappers are identified by
 * their framework hierarchy. Non-standard ApplicationLike/RealApplication
 * chains are discovered from constructor relationships rather than hardcoded
 * app-specific class names.
 */
internal sealed interface ApplicationBootstrap {
    data class EmbeddedApplication(
        val className: String,
        val allowManifestFallback: Boolean
    ) : ApplicationBootstrap

    data class TinkerApplication(
        val wrapperClass: Class<out Application>,
        val allowManifestFallback: Boolean = true
    ) : ApplicationBootstrap

    data class ApplicationLikePair(
        val likeClass: Class<*>,
        val realClass: Class<out Application>,
        val allowManifestFallback: Boolean = true
    ) : ApplicationBootstrap

    data class UnsupportedHardenedApplication(
        val fingerprint: String,
        val reason: String
    ) : ApplicationBootstrap

    data object ManifestApplication : ApplicationBootstrap
}

internal object BootstrapClassNamePolicy {

    fun isExternalApplicationClass(
        className: String?,
        packageName: String
    ): Boolean {
        if (className.isNullOrBlank()) return false
        return !className.startsWith("$packageName.")
    }

    fun isPlausibleApplication(className: String): Boolean {
        if ('$' in className) return false
        return className.substringAfterLast('.').contains("Application", ignoreCase = true)
    }

    fun isPlausibleApplicationLike(className: String): Boolean {
        if ('$' in className) return false
        val simpleName = className.substringAfterLast('.')
        return simpleName.contains("Application", ignoreCase = true) ||
            simpleName.contains("AppLike", ignoreCase = true)
    }

    fun embeddedApplicationScore(className: String, packageName: String): Int {
        if (!isPlausibleApplication(className)) return -1
        if (!className.startsWith("$packageName.")) return -1
        val simpleName = className.substringAfterLast('.')
        var score = when {
            simpleName.equals("Application", ignoreCase = true) -> 140
            simpleName.endsWith("Application", ignoreCase = true) -> 110
            else -> 70
        }
        if (className.substringBeforeLast('.') == packageName) score += 40
        if (simpleName.contains("Wrapper", ignoreCase = true) ||
            simpleName.contains("Proxy", ignoreCase = true) ||
            simpleName.contains("Delegate", ignoreCase = true)
        ) {
            score -= 30
        }
        return score
    }

    fun applicationLikeScore(className: String, packageName: String): Int {
        if (!isPlausibleApplicationLike(className)) return -1
        if (!className.startsWith("$packageName.")) return -1
        val simpleName = className.substringAfterLast('.')
        var score = 40
        if (simpleName.endsWith("ApplicationLike", ignoreCase = true)) score += 100
        if (simpleName.contains("Patch", ignoreCase = true)) score += 20
        if (className.substringBeforeLast('.') == packageName) score += 20
        return score
    }
}

internal object ApplicationBootstrapResolver {

    private const val TAG = "AppBootstrapResolver"
    private const val TINKER_APPLICATION_LIKE = "ApplicationLike"
    private val tinkerWrapperTypes = setOf("TinkerApplication", "MuteApplication")

    private data class LikeCandidate(
        val type: Class<*>,
        val constructor: Constructor<*>,
        val score: Int
    )

    fun resolve(
        spec: RuntimeApplicationSpec,
        loader: ClassLoader
    ): ApplicationBootstrap {
        val manifestClass = spec.applicationInfo.className
        val externalWrapper = hasExternalApplicationWrapper(spec, loader)
        if (externalWrapper) {
            findApplicationLikePair(spec, loader)
                ?.let { return it }
            findEmbeddedApplication(spec, loader)
                ?.let {
                    return ApplicationBootstrap.EmbeddedApplication(
                        className = it,
                        allowManifestFallback = true
                    )
                }
            Log.w(
                TAG,
                "external Application wrapper has no embedded target: $manifestClass"
            )
        }
        tinkerWrapper(spec, loader)
            ?.let { return ApplicationBootstrap.TinkerApplication(it) }
        Log.d(TAG, "using manifest Application: ${manifestClass ?: "<default>"}")
        return ApplicationBootstrap.ManifestApplication
    }

    private fun hasExternalApplicationWrapper(
        spec: RuntimeApplicationSpec,
        loader: ClassLoader
    ): Boolean {
        val manifestClass = spec.applicationInfo.className ?: return false
        if (!BootstrapClassNamePolicy.isExternalApplicationClass(
                manifestClass,
                spec.packageName
            )
        ) {
            return false
        }
        val wrapper = loadClass(loader, manifestClass) ?: return false
        return Application::class.java.isAssignableFrom(wrapper)
    }

    fun createApplicationLike(
        type: Class<*>,
        base: Application
    ): Any {
        val constructor = findApplicationLikeConstructor(type)
            ?: error("ApplicationLike constructor not found: ${type.name}")
        return constructor.newInstance(
            base,
            0x0f,
            true,
            SystemClock.elapsedRealtime(),
            System.currentTimeMillis(),
            null
        )
    }

    fun createRealApplication(
        type: Class<out Application>,
        base: Application,
        like: Any
    ): Application {
        val constructor = findRealApplicationConstructor(type, like.javaClass)
            ?: error("real Application constructor not found: ${type.name}")
        return constructor.newInstance(base, like) as Application
    }

    internal fun matchesApplicationLikeConstructor(
        parameterTypes: List<Class<*>>
    ): Boolean {
        if (parameterTypes.size != 6) return false
        if (!parameterTypes[0].isAssignableFrom(Application::class.java)) return false
        if (!isInteger(parameterTypes[1])) return false
        if (!isBoolean(parameterTypes[2])) return false
        if (!isLong(parameterTypes[3]) || !isLong(parameterTypes[4])) return false
        return !parameterTypes[5].isPrimitive
    }

    internal fun matchesRealApplicationConstructor(
        parameterTypes: List<Class<*>>,
        likeClass: Class<*>
    ): Boolean =
        parameterTypes.size == 2 &&
            parameterTypes[0].isAssignableFrom(Application::class.java) &&
            !parameterTypes[1].isPrimitive &&
            parameterTypes[1].isAssignableFrom(likeClass)

    internal fun hasTinkerDelegateField(fieldTypes: List<Class<*>>): Boolean =
        fieldTypes.any(Application::class.java::isAssignableFrom)

    private fun findEmbeddedApplication(
        spec: RuntimeApplicationSpec,
        loader: ClassLoader
    ): String? {
        val candidates = classNames(spec)
            .asSequence()
            .map { it to BootstrapClassNamePolicy.embeddedApplicationScore(it, spec.packageName) }
            .filter { it.second >= 0 }
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenBy { it.first.length }
                    .thenBy { it.first }
            )
            .toList()
        for ((name, _) in candidates) {
            val candidate = loadClass(loader, name)
                ?.takeIf { Application::class.java.isAssignableFrom(it) }
                ?.asSubclass(Application::class.java)
                ?: continue
            if (!isInstantiable(candidate)) continue
            if (runCatching { candidate.getConstructor() }.isFailure) continue
            if (candidate.name == spec.applicationInfo.className) continue
            Log.d(TAG, "NIS embedded Application: ${candidate.name}")
            return candidate.name
        }
        Log.w(TAG, "no generic NIS embedded Application for ${spec.packageName}")
        return null
    }

    private fun tinkerWrapper(
        spec: RuntimeApplicationSpec,
        loader: ClassLoader
    ): Class<out Application>? {
        val manifestClass = spec.applicationInfo.className
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val wrapper = loadClass(loader, manifestClass)
            ?.takeIf { Application::class.java.isAssignableFrom(it) }
            ?.asSubclass(Application::class.java)
            ?: return null
        val matches = generateSequence(wrapper as Class<*>?) { it.superclass }
            .any { type ->
                type.simpleName in tinkerWrapperTypes
            }
        val hasDelegateField = generateSequence(wrapper as Class<*>?) { it.superclass }
            .any { type ->
                hasTinkerDelegateField(type.declaredFields.map { it.type })
            }
        if (!matches || !hasDelegateField) return null
        Log.d(TAG, "Tinker wrapper Application: ${wrapper.name}")
        return wrapper
    }

    private fun findApplicationLikePair(
        spec: RuntimeApplicationSpec,
        loader: ClassLoader
    ): ApplicationBootstrap.ApplicationLikePair? {
        val classNames = classNames(spec).filter {
            BootstrapClassNamePolicy.isPlausibleApplicationLike(it) ||
                BootstrapClassNamePolicy.isPlausibleApplication(it)
        }
        val likes = classNames.asSequence()
            .mapNotNull { name ->
                runCatching {
                    val type = loadClass(loader, name) ?: return@runCatching null
                    if (!isInstantiable(type) || type.isInterface) return@runCatching null
                    val constructor = findApplicationLikeConstructor(type)
                        ?: return@runCatching null
                    var score = BootstrapClassNamePolicy.applicationLikeScore(
                        name,
                        spec.packageName
                    )
                    if (score < 0) return@runCatching null
                    if (hierarchyContains(type, TINKER_APPLICATION_LIKE)) score += 120
                    score += inheritanceDepth(type).coerceAtMost(8) * 5
                    LikeCandidate(type, constructor, score)
                }.getOrNull()
            }
            .sortedWith(
                compareByDescending<LikeCandidate> { it.score }
                    .thenBy { it.type.name.length }
                    .thenBy { it.type.name }
            )
            .toList()
        if (likes.isEmpty()) return null

        val realClasses = classNames.asSequence()
            .filter { it.startsWith("${spec.packageName}.") }
            .mapNotNull { name ->
                runCatching {
                    val type = loadClass(loader, name)
                        ?.takeIf { Application::class.java.isAssignableFrom(it) }
                        ?.asSubclass(Application::class.java)
                        ?: return@runCatching null
                    type.takeIf(::isInstantiable)
                }.getOrNull()
            }
            .distinct()
            .sortedWith(
                compareByDescending<Class<out Application>> {
                    if (it.simpleName.contains("Real", ignoreCase = true)) 1 else 0
                }.thenBy { it.name.length }.thenBy { it.name }
            )
            .toList()

        for (like in likes) {
            for (real in realClasses) {
                if (real == like.type) continue
                if (findRealApplicationConstructor(real, like.type) == null) continue
                Log.d(
                    TAG,
                    "ApplicationLike pair like=${like.type.name} real=${real.name}"
                )
                return ApplicationBootstrap.ApplicationLikePair(like.type, real)
            }
        }
        Log.w(TAG, "ApplicationLike classes found but no matching real Application")
        return null
    }

    private fun findApplicationLikeConstructor(type: Class<*>): Constructor<*>? =
        runCatching {
            type.constructors.firstOrNull {
                matchesApplicationLikeConstructor(it.parameterTypes.toList())
            }
        }.getOrNull()

    private fun findRealApplicationConstructor(
        type: Class<out Application>,
        likeClass: Class<*>
    ): Constructor<*>? = runCatching {
        type.constructors.firstOrNull {
            matchesRealApplicationConstructor(it.parameterTypes.toList(), likeClass)
        }
    }.getOrNull()

    private fun classNames(spec: RuntimeApplicationSpec): List<String> =
        ApkClassIndex.classNames(listOf(spec.baseApkPath) + spec.splitApkPaths)

    private fun loadClass(loader: ClassLoader, name: String): Class<*>? =
        runCatching { loader.loadClass(name) }
            .getOrNull()
            ?.takeIf { it.classLoader === loader }

    private fun isInstantiable(type: Class<*>): Boolean =
        Modifier.isPublic(type.modifiers) &&
            !Modifier.isAbstract(type.modifiers) &&
            !type.isInterface

    internal fun hierarchyContains(type: Class<*>, simpleName: String): Boolean {
        val visited = mutableSetOf<Class<*>>()
        fun visit(current: Class<*>): Boolean {
            if (!visited.add(current)) return false
            if (current.simpleName.equals(simpleName, ignoreCase = true)) return true
            if (current.interfaces.any(::visit)) return true
            return current.superclass?.let(::visit) == true
        }
        return visit(type)
    }

    private fun inheritanceDepth(type: Class<*>): Int {
        var depth = 0
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            depth++
            current = current.superclass
        }
        return depth
    }

    private fun isInteger(type: Class<*>): Boolean =
        type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType

    private fun isBoolean(type: Class<*>): Boolean =
        type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType

    private fun isLong(type: Class<*>): Boolean =
        type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType
}

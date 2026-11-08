package com.coke.nestspace.container

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.coke.nestspace.container.loader.withContainerClassLoader
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hooks the target's request-parameter provider for apps that attach an
 * asynchronously encrypted installed-app snapshot to first-launch requests.
 *
 * The public interface remains the same. Only `getAppListLimited()` is
 * intercepted: if the snapshot is still empty, the hook starts the target's
 * own initializer and waits briefly on the calling network thread. Other API
 * calls are delegated to the original singleton.
 */
internal class TargetRequestParameterHook private constructor(
    private val apiField: Field,
    private val delegate: Any,
    private val context: Context,
    private val snapshot: AppListSnapshot,
    proxyClass: Class<*>
) : AutoCloseable {
    private val initializerAttempted = AtomicBoolean(false)
    private val waitLock = Any()
    private val proxy: Any = Proxy.newProxyInstance(
        proxyClass.classLoader,
        arrayOf(proxyClass),
        ApiHandler()
    )

    fun install(): Boolean {
        if (apiField.get(null) === proxy) return true
        return runCatching {
            apiField.isAccessible = true
            runCatching { apiField.set(null, proxy) }
                .getOrElse {
                    clearFinalModifier(apiField)
                    apiField.set(null, proxy)
                }
            apiField.get(null) === proxy
        }.onFailure {
            Log.w(TAG, "request parameter hook install failed", it)
        }.getOrDefault(false)
    }

    private fun appList(): String {
        snapshot.read()?.takeIf { it.isNotEmpty() }?.let {
            Log.d(TAG, "request parameter snapshot ready length=${it.length}")
            return it
        }

        synchronized(waitLock) {
            snapshot.read()?.takeIf { it.isNotEmpty() }?.let { return it }
            ensureInitializer()
            snapshot.populateFromPackageManager(context)

            val startedAt = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - startedAt < MAX_WAIT_MS) {
                snapshot.read()?.takeIf { it.isNotEmpty() }?.let {
                    Log.d(
                        TAG,
                        "request parameter snapshot ready length=${it.length} " +
                            "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms"
                    )
                    return it
                }
                Thread.sleep(POLL_MS)
            }

            Log.w(
                TAG,
                "request parameter snapshot timeout after " +
                    "${SystemClock.elapsedRealtime() - startedAt}ms"
            )
            return invokeDelegate(GET_APP_LIST_METHOD, emptyArray()) as? String ?: ""
        }
    }

    private fun ensureInitializer() {
        if (!initializerAttempted.compareAndSet(false, true)) return
        runCatching { snapshot.runInitializer() }
            .onSuccess { Log.d(TAG, "request parameter initializer completed") }
            .onFailure { Log.w(TAG, "request parameter initializer failed", it) }
    }

    private fun invokeDelegate(methodName: String, args: Array<out Any?>): Any? {
        val method = delegate.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterCount == args.size
        } ?: return null
        return try {
            method.invoke(delegate, *args)
        } catch (t: InvocationTargetException) {
            throw t.targetException
        }
    }

    override fun close() {
        runCatching {
            if (apiField.get(null) === proxy) apiField.set(null, delegate)
        }.onFailure {
            Log.w(TAG, "request parameter hook restore failed", it)
        }
    }

    private inner class ApiHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "TargetRequestParameterHook"
                }
            }
            if (method.name == GET_APP_LIST_METHOD && method.parameterCount == 0) {
                return appList()
            }
            return try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (t: InvocationTargetException) {
                throw t.targetException
            }
        }
    }

    private class AppListSnapshot(
        private val instance: Any,
        private val encryptedField: Field,
        private val apiImplementation: Any,
        private val initializer: Method,
        private val updaterConstructor: java.lang.reflect.Constructor<*>,
        private val updateMethod: Method
    ) {
        fun read(): String? = encryptedField.get(instance) as? String

        fun runInitializer() {
            initializer.invoke(apiImplementation)
        }

        fun populateFromPackageManager(context: Context) {
            val packages = context.packageManager.getInstalledPackages(0)
            val appList = JSONArray()
            for (pkg in packages) {
                val name = pkg.packageName ?: continue
                appList.put(JSONObject().put("packageName", name))
            }
            if (appList.length() == 0) return

            val updater = updaterConstructor.newInstance(
                instance,
                SystemClock.elapsedRealtime()
            )
            updateMethod.invoke(updater, appList)
            Log.d(
                TAG,
                "request parameter snapshot populated packages=${appList.length()} " +
                    "length=${read()?.length ?: 0}"
            )
        }
    }

    companion object {
        private const val TAG = "TargetRequestParameterHook"
        private const val API_CLASS =
            "com.dragon.read.component.biz.api.NsUgApi"
        private const val HOLDER_CLASS = "ok7.o\$a"
        private const val GET_APP_LIST_METHOD = "getAppListLimited"
        private const val POLL_MS = 50L
        private const val MAX_WAIT_MS = 3_000L

        fun install(classLoader: ClassLoader, context: Context): TargetRequestParameterHook? =
            runCatching {
                withContainerClassLoader(classLoader) {
                    val apiClass = classLoader.loadClass(API_CLASS)
                    val apiField = apiClass.getField("IMPL")
                    val delegate = apiField.get(null)
                        ?: return@withContainerClassLoader null
                    val initializer = apiClass.getMethod("getRunInitialize")

                    val holderClass = classLoader.loadClass(HOLDER_CLASS)
                    val holder = holderClass.getField("a").get(null)
                        ?: return@withContainerClassLoader null
                    val encryptedField = holder.javaClass
                        .getDeclaredField("a")
                        .apply { isAccessible = true }
                    val updaterClass = classLoader.loadClass("ok7.m")
                    val updaterConstructor = updaterClass.getDeclaredConstructor(
                        holder.javaClass,
                        Long::class.javaPrimitiveType
                    ).apply { isAccessible = true }
                    val updateMethod = updaterClass.getMethod(
                        "c",
                        JSONArray::class.java
                    )
                    val snapshot = AppListSnapshot(
                        instance = holder,
                        encryptedField = encryptedField,
                        apiImplementation = delegate,
                        initializer = initializer,
                        updaterConstructor = updaterConstructor,
                        updateMethod = updateMethod
                    )

                    TargetRequestParameterHook(
                        apiField = apiField,
                        delegate = delegate,
                        context = context,
                        snapshot = snapshot,
                        proxyClass = apiClass
                    )
                }
            }.onFailure {
                Log.w(TAG, "request parameter hook unavailable", it)
            }.getOrNull()?.also { hook ->
                if (hook.install()) {
                    Log.d(TAG, "request parameter hook installed")
                }
            }

        private fun clearFinalModifier(field: Field) {
            runCatching {
                val modifiers = Field::class.java.getDeclaredField("modifiers")
                    .apply { isAccessible = true }
                modifiers.setInt(field, field.modifiers and Modifier.FINAL.inv())
            }
        }
    }
}

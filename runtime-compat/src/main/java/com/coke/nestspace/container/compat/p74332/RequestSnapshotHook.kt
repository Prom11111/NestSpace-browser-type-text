package com.coke.nestspace.container.compat.p74332

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Supplies an async installed-app snapshot required by this APK profile. */
internal class RequestSnapshotHook private constructor(
    private val apiField: Field,
    private val delegate: Any,
    private val context: Context,
    private val snapshot: AppListSnapshot,
    proxyClass: Class<*>
) : AutoCloseable {
    private val initializerAttempted = AtomicBoolean(false)
    private val initializationFinished = CountDownLatch(1)
    private val workerClassLoader = Thread.currentThread().contextClassLoader
    private val initializationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nestspace-request-params").apply {
            isDaemon = true
            contextClassLoader = workerClassLoader
        }
    }
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

    fun warmUp() {
        startInitialization()
    }

    private fun appList(): String {
        snapshot.read()?.takeIf { it.isNotEmpty() }?.let {
            Log.d(TAG, "request parameter snapshot ready length=${it.length}")
            return it
        }

        startInitialization()
        val startedAt = SystemClock.elapsedRealtime()
        val finished = runCatching {
            initializationFinished.await(MAX_WAIT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        snapshot.read()?.takeIf { it.isNotEmpty() }?.let {
            Log.d(
                TAG,
                "request parameter snapshot ready length=${it.length} " +
                    "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms"
            )
            return it
        }
        if (!finished) {
            Log.w(
                TAG,
                "request parameter snapshot timeout after " +
                    "${SystemClock.elapsedRealtime() - startedAt}ms"
            )
        }
        return invokeDelegate(GET_APP_LIST_METHOD, emptyArray()) as? String ?: ""
    }

    private fun startInitialization() {
        if (!initializerAttempted.compareAndSet(false, true)) return
        runCatching {
            initializationExecutor.execute {
                try {
                    runCatching { snapshot.runInitializer() }
                        .onSuccess { Log.d(TAG, "request parameter initializer completed") }
                        .onFailure { Log.w(TAG, "request parameter initializer failed", it) }
                    runCatching { snapshot.populateFromPackageManager(context) }
                        .onFailure {
                            Log.w(TAG, "request parameter snapshot population failed", it)
                        }
                } finally {
                    initializationFinished.countDown()
                }
            }
        }.onFailure {
            initializationFinished.countDown()
            Log.w(TAG, "request parameter initialization rejected", it)
        }
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
        initializationExecutor.shutdownNow()
        initializationFinished.countDown()
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
                    else -> "RequestSnapshotHook"
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
        private const val TAG = "RequestSnapshotHook"
        private const val GET_APP_LIST_METHOD = "getAppListLimited"
        private const val MAX_WAIT_MS = 3_000L

        fun install(
            profile: ProfileP74332.Classes,
            classLoader: ClassLoader,
            context: Context
        ): RequestSnapshotHook? =
            runCatching {
                withContainerClassLoader(classLoader) {
                    val apiClass = profile.requestApi
                    val apiField = apiClass.getField("IMPL")
                    val delegate = apiField.get(null)
                        ?: return@withContainerClassLoader null
                    val initializer = apiClass.getMethod("getRunInitialize")

                    val holderClass = profile.requestHolder
                    val holder = holderClass.getField("a").get(null)
                        ?: return@withContainerClassLoader null
                    val encryptedField = holder.javaClass
                        .getDeclaredField("a")
                        .apply { isAccessible = true }
                    val updaterClass = profile.requestUpdater
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

                    RequestSnapshotHook(
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
                    hook.warmUp()
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

@file:Suppress("DEPRECATION")

package com.coke.nestspace.container.location

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.coke.nestspace.container.ContainerContext
import com.coke.nestspace.container.HiddenApiCompat
import com.coke.nestspace.container.identity.HostIdentity
import com.coke.nestspace.location.MockLocationSettings
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A virtual [LocationManager] for the container.
 *
 * The target APK is not installed for the host UID, so it cannot be given the
 * real location service under its own package. Instead the manager is built
 * against a local `ILocationManager` proxy:
 *
 * - location reads and listener registrations are answered from
 *   [MockLocationSettings];
 * - metadata calls fall back to the real service with the package rewritten to
 *   the host package;
 * - when simulation is disabled, everything falls back to the real service.
 *
 * The simulated fix is delivered to LocationListener and PendingIntent
 * registrations once per second, until the target unregisters.
 */
internal object ContainerLocationManager {

    private const val TAG = "ContainerLocation"

    /** Hidden on older releases; its full name is stable across versions. */
    private const val LOCATION_REQUEST = "android.location.LocationRequest"

    /** Live update interval for simulated fixes. */
    private const val TICK_MS = 1_000L

    private val PROVIDERS = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        "fused",
        LocationManager.PASSIVE_PROVIDER
    )

    /**
     * Returns a container LocationManager, or null when the framework layout is
     * unexpected (the caller then falls back to the real manager).
     */
    fun create(context: ContainerContext): LocationManager? {
        HiddenApiCompat.ensure()
        return runCatching {
            val iface = Class.forName("android.location.ILocationManager")
            val hostContext = context.baseContext.applicationContext
            val hostManager = hostContext.getSystemService(Context.LOCATION_SERVICE)
                as? LocationManager
            val realService = hostManager?.let(::serviceOf)
            val service = Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface),
                MockLocationService(context, hostContext, realService)
            )
            construct(context, iface, service)
                ?: patchHostManager(hostManager, service)
        }.onFailure {
            Log.w(TAG, "virtual LocationManager unavailable", it)
        }.getOrNull()
    }

    /**
     * Preferred path: a fresh LocationManager whose mContext is the container
     * context. This keeps the target's Context attribution and never leaks the
     * proxy into the host manager.
     */
    private fun construct(
        context: ContainerContext,
        iface: Class<*>,
        service: Any
    ): LocationManager? = runCatching {
        val managerClass = Class.forName("android.location.LocationManager")
        val constructor = managerClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.size == 2 &&
                candidate.parameterTypes[0] == Context::class.java &&
                candidate.parameterTypes[1] == iface
        } ?: return@runCatching null
        constructor.isAccessible = true
        constructor.newInstance(context, service) as LocationManager
    }.getOrNull()

    /**
     * Older layouts may hide the constructor more aggressively. Replacing the
     * mService field of the process-local host manager still virtualizes every
     * LocationManager this process can obtain.
     */
    private fun patchHostManager(manager: LocationManager?, service: Any): LocationManager? {
        if (manager == null) return null
        return runCatching {
            val field = manager.javaClass.declaredFields.firstOrNull {
                it.type.name == "android.location.ILocationManager"
            } ?: return@runCatching null
            field.isAccessible = true
            field.set(manager, service)
            manager
        }.getOrNull()
    }

    private fun serviceOf(manager: LocationManager): Any? {
        var type: Class<*>? = manager.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull {
                it.type.name == "android.location.ILocationManager"
            }?.let { field ->
                return runCatching {
                    field.isAccessible = true
                    field.get(manager)
                }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    private class MockLocationService(
        private val context: ContainerContext,
        private val hostContext: Context,
        private val realService: Any?
    ) : InvocationHandler {

        private val mainHandler = Handler(Looper.getMainLooper())
        private val listenerClass: Class<*>? = runCatching {
            Class.forName("android.location.ILocationListener")
        }.getOrNull()
        private val callbackClass: Class<*>? = runCatching {
            Class.forName("android.location.ILocationCallback")
        }.getOrNull()
        private val listenerMethods: List<Method> =
            listenerClass?.methods?.filter { it.name == "onLocationChanged" }.orEmpty()
        private val callbackMethods: List<Method> =
            callbackClass?.methods?.filter { it.name == "onLocation" }.orEmpty()

        private val listeners = Collections.newSetFromMap(
            ConcurrentHashMap<Any, Boolean>()
        )
        private val pendingIntents = ConcurrentHashMap<PendingIntent, Boolean>()

        /** Registrations handed to the real service while simulation is off. */
        private val forwarded = Collections.newSetFromMap(
            ConcurrentHashMap<Any, Boolean>()
        )

        @Volatile
        private var provider = LocationManager.GPS_PROVIDER

        private val tickLock = Any()

        @Volatile
        private var ticking = false

        private val ticker = object : Runnable {
            override fun run() {
                val active = synchronized(tickLock) {
                    if (listeners.isEmpty() && pendingIntents.isEmpty()) {
                        ticking = false
                        false
                    } else {
                        true
                    }
                }
                if (!active) return
                dispatch()
                mainHandler.postDelayed(this, TICK_MS)
            }
        }

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            val name = method.name
            return when {
                name == "toString" -> "NestSpaceMockLocation"
                name == "hashCode" -> System.identityHashCode(proxy)
                name == "equals" -> proxy === args?.firstOrNull()
                isLastLocation(name, method) -> lastLocation(method, args)
                name == "getCurrentLocation" -> {
                    if (simulated()) {
                        requireLocationPermission()
                        registerLocal(args, oneShot = true)
                        defaultValue(method.returnType)
                    } else {
                        forward(method, args)
                    }
                }
                isRegistration(name) -> {
                    if (simulated()) {
                        requireLocationPermission()
                        registerLocal(args, oneShot = false)
                        defaultValue(method.returnType)
                    } else {
                        val result = forward(method, args)
                        trackForwarded(args)
                        result
                    }
                }
                isUnregistration(name) -> {
                    val wasForwarded = args?.any { it != null && forwarded.contains(it) } == true
                    args?.forEach { value ->
                        if (value == null) return@forEach
                        if (isListener(value)) listeners.remove(value)
                        if (value is PendingIntent) pendingIntents.remove(value)
                        forwarded.remove(value)
                    }
                    if (wasForwarded) forward(method, args) else defaultValue(method.returnType)
                }
                name.startsWith("isProviderEnabled") ||
                    name.startsWith("isLocationEnabled") ->
                    if (simulated()) true else forward(method, args)
                name == "getAllProviders" || name == "getProviders" ->
                    if (simulated()) PROVIDERS.toList() else forward(method, args)
                name == "hasProvider" ->
                    if (simulated()) {
                        (args?.firstOrNull() as? String) in PROVIDERS
                    } else {
                        forward(method, args)
                    }
                name.contains("getBestProvider") ->
                    if (simulated()) bestProvider(args) else forward(method, args)
                else -> forward(method, args)
            }
        }

        private fun isLastLocation(name: String, method: Method): Boolean =
            (name.startsWith("getLastLocation") || name == "getLastKnownLocation") &&
                Location::class.java.isAssignableFrom(method.returnType)

        private fun isRegistration(name: String): Boolean =
            name.contains("requestLocationUpdates") ||
                name.contains("registerLocationListener") ||
                name.contains("registerLocationPendingIntent")

        private fun isUnregistration(name: String): Boolean =
            name.contains("removeLocationUpdates") ||
                name.contains("removeUpdates") ||
                name.contains("unregisterLocationListener") ||
                name.contains("unregisterLocationPendingIntent")

        private fun lastLocation(method: Method, args: Array<Any?>?): Location? {
            val config = MockLocationSettings.load(hostContext)
            if (!config.enabled) return forward(method, args) as? Location
            requireLocationPermission()
            return buildLocation(config, providerOf(args))
        }

        private fun simulated(): Boolean =
            MockLocationSettings.load(hostContext).enabled

        private fun registerLocal(args: Array<Any?>?, oneShot: Boolean) {
            val added = mutableListOf<Any>()
            args?.forEach { value ->
                when {
                    isListener(value) -> {
                        listeners.add(value!!)
                        added += value
                    }
                    value is PendingIntent -> {
                        pendingIntents[value] = true
                        added += value
                    }
                }
            }
            if (added.isEmpty()) return
            provider = providerOf(args)
            if (oneShot) {
                mainHandler.post {
                    val config = MockLocationSettings.load(hostContext)
                    if (config.enabled && hasLocationPermission()) {
                        val location = buildLocation(config, provider)
                        added.forEach { value ->
                            runCatching { deliverValue(value, location) }
                                .onFailure { Log.w(TAG, "one-shot delivery failed", it) }
                        }
                    }
                    added.forEach(::remove)
                }
            } else {
                ensureTicker()
                mainHandler.post { dispatch() }
            }
        }

        private fun trackForwarded(args: Array<Any?>?) {
            args?.forEach { value ->
                if (isListener(value) || value is PendingIntent) {
                    forwarded.add(value!!)
                }
            }
        }

        private fun remove(value: Any) {
            listeners.remove(value)
            if (value is PendingIntent) pendingIntents.remove(value)
        }

        private fun isListener(value: Any?): Boolean =
            value != null &&
                (listenerClass?.isInstance(value) == true ||
                    callbackClass?.isInstance(value) == true)

        /**
         * The callback shape changed across releases: older frameworks use
         * onLocationChanged(Location), Android 17 uses
         * onLocationChanged(List<Location>, IRemoteCallback) for listeners and
         * ILocationCallback.onLocation(Location) for one-shot requests.
         */
        private fun deliver(target: Any, location: Location) {
            val methods = when {
                listenerClass?.isInstance(target) == true -> listenerMethods
                callbackClass?.isInstance(target) == true -> callbackMethods
                else -> return
            }
            val method = methods.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0] == Location::class.java
            } ?: methods.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0] == List::class.java
            } ?: methods.firstOrNull {
                it.parameterCount == 2 && it.parameterTypes[0] == List::class.java
            } ?: return
            val args: Array<Any?> = when {
                method.parameterTypes[0] == Location::class.java -> arrayOf(location)
                method.parameterCount == 1 -> arrayOf(listOf(location))
                else -> arrayOf(listOf(location), null)
            }
            method.isAccessible = true
            method.invoke(target, *args)
        }

        private fun ensureTicker() {
            synchronized(tickLock) {
                if (ticking) return
                ticking = true
            }
            mainHandler.post(ticker)
        }

        private fun dispatch() {
            val config = MockLocationSettings.load(hostContext)
            if (!config.enabled || !hasLocationPermission()) return
            val location = buildLocation(config, provider)
            listeners.forEach { listener ->
                runCatching { deliver(listener, location) }
                    .onFailure {
                        Log.w(TAG, "listener delivery failed", it)
                        listeners.remove(listener)
                    }
            }
            pendingIntents.keys.forEach { intent ->
                runCatching { deliverValue(intent, location) }
                    .onFailure {
                        Log.w(TAG, "PendingIntent delivery failed", it)
                        pendingIntents.remove(intent)
                    }
            }
        }

        private fun deliverValue(target: Any, location: Location) {
            if (target is PendingIntent) {
                val extras = Intent().putExtra(
                    LocationManager.KEY_LOCATION_CHANGED,
                    location
                )
                target.send(hostContext, 0, extras)
            } else {
                deliver(target, location)
            }
        }

        private fun buildLocation(
            config: MockLocationSettings.Config,
            provider: String
        ): Location = Location(provider).apply {
            latitude = config.latitude
            longitude = config.longitude
            accuracy = MockLocationSettings.ACCURACY_METERS
            speed = 0f
            bearing = 0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = MockLocationSettings.ACCURACY_METERS
                speedAccuracyMetersPerSecond = 0f
                bearingAccuracyDegrees = 0f
            }
        }

        private fun providerOf(args: Array<Any?>?): String {
            args?.forEach { value ->
                when {
                    value == null -> Unit
                    value.javaClass.name == LOCATION_REQUEST -> {
                        requestProvider(value)?.let { return it }
                    }
                    value is Criteria -> Unit
                    value is String -> if (value in PROVIDERS) return value
                }
            }
            return LocationManager.GPS_PROVIDER
        }

        private fun requestProvider(request: Any): String? =
            runCatching {
                request.javaClass.getMethod("getProvider").invoke(request) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }

        private fun bestProvider(args: Array<Any?>?): String {
            val criteria = args?.firstOrNull { it is Criteria } as? Criteria
            val accuracy = criteria?.accuracy
            return if (accuracy == Criteria.ACCURACY_COARSE) {
                LocationManager.NETWORK_PROVIDER
            } else {
                LocationManager.GPS_PROVIDER
            }
        }

        private fun hasLocationPermission(): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        private fun requireLocationPermission() {
            if (hasLocationPermission()) return
            throw SecurityException(
                "Neither user nor current process has " +
                    Manifest.permission.ACCESS_FINE_LOCATION + " or " +
                    Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        private fun forward(method: Method, args: Array<Any?>?): Any? {
            val service = realService ?: return defaultValue(method.returnType)
            val target = context.packageName
            val host = HostIdentity.packageName
            val callArgs = rewritePackage((args ?: emptyArray()).copyOf(), target, host)
            return try {
                method.invoke(service, *callArgs)
            } catch (t: InvocationTargetException) {
                val cause = t.targetException
                if (cause is SecurityException) throw cause
                Log.w(TAG, "delegate ${method.name} failed", cause)
                defaultValue(method.returnType)
            } catch (t: Throwable) {
                Log.w(TAG, "delegate ${method.name} failed", t)
                defaultValue(method.returnType)
            }
        }

        private fun rewritePackage(
            values: Array<Any?>,
            target: String,
            host: String
        ): Array<Any?> {
            if (target.isEmpty() || target == host) return values
            for (index in values.indices) {
                if (values[index] == target) values[index] = host
            }
            return values
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Void.TYPE -> null
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Char::class.javaPrimitiveType -> '\u0000'
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }
}

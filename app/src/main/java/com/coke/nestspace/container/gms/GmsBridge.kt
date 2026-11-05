package com.coke.nestspace.container.gms

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges Google Play services bindings into the host identity.
 *
 * GMS runs in its own process and rejects a caller that claims the virtual
 * target package under the host UID ("Unknown calling package name"). The
 * client classes usually live in GMS's own class loader, so the broker
 * interface is not visible to the target class loader. We therefore bridge at
 * the Binder transaction level: parse GetServiceRequest with the GMS APK's
 * classes, rewrite its package to the host package, then call the real broker.
 */
object GmsBridge {

    private const val TAG = "GmsBridge"
    private const val GMS = "com.google.android.gms"
    private const val VENDING = "com.android.vending"
    private const val BROKER = "com.google.android.gms.common.internal.IGmsServiceBroker"

    private val connections = ConcurrentHashMap<ServiceConnection, ServiceConnection>()
    private val layoutInspected = java.util.concurrent.atomic.AtomicBoolean(false)

    fun isGmsIntent(intent: Intent): Boolean {
        val pkg = intent.component?.packageName ?: intent.`package` ?: return false
        return pkg == GMS || pkg.startsWith("$GMS.") ||
            pkg == VENDING || pkg.startsWith("$VENDING.")
    }

    fun bind(
        classLoader: ClassLoader,
        gmsClassLoader: ClassLoader?,
        intent: Intent,
        original: ServiceConnection,
        targetPackage: String,
        hostPackage: String,
        doBind: (ServiceConnection) -> Boolean
    ): Boolean {
        if (!isGmsIntent(intent)) return doBind(original)
        Log.d(TAG, "binding GMS action=${intent.action} comp=${intent.component}")
        val wrapped = WrappedConnection(
            classLoader,
            gmsClassLoader,
            original,
            targetPackage,
            hostPackage
        )
        connections[original] = wrapped
        val bound = runCatching { doBind(wrapped) }.getOrDefault(false)
        if (!bound) connections.remove(original)
        return bound
    }

    fun unbind(original: ServiceConnection, doUnbind: (ServiceConnection) -> Unit) {
        val wrapped = connections.remove(original) ?: original
        doUnbind(wrapped)
    }

    private class WrappedConnection(
        private val classLoader: ClassLoader,
        private val gmsClassLoader: ClassLoader?,
        private val original: ServiceConnection,
        private val targetPackage: String,
        private val hostPackage: String
    ) : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val connectionLoader = original.javaClass.classLoader
            original.onServiceConnected(
                name,
                service?.let {
                    wrap(it, connectionLoader, classLoader, gmsClassLoader, targetPackage, hostPackage)
                }
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            original.onServiceDisconnected(name)
        }

        override fun onBindingDied(name: ComponentName?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { original.onBindingDied(name) }
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { original.onNullBinding(name) }
            }
        }
    }

    private fun wrap(
        binder: IBinder,
        connectionLoader: ClassLoader?,
        classLoader: ClassLoader,
        gmsClassLoader: ClassLoader?,
        targetPackage: String,
        hostPackage: String
    ): IBinder {
        // Local proxy path: only works when the client and our proxy share the
        // same interface Class identity.
        val clientIface = load(BROKER, connectionLoader)
            ?: load(BROKER, classLoader)
        if (clientIface != null) {
            val stub = runCatching {
                Class.forName("$BROKER\$Stub", false, clientIface.classLoader)
            }.getOrNull()
            val real = stub?.let {
                runCatching {
                    it.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                }.getOrNull()
            }
            val proxy = real?.let {
                runCatching {
                    Proxy.newProxyInstance(
                        clientIface.classLoader ?: classLoader,
                        arrayOf(clientIface),
                        GmsInvocationHandler(it, targetPackage, hostPackage)
                    ) as IInterface
                }.getOrNull()
            }
            if (proxy != null) {
                Log.d(TAG, "wrapped broker with local proxy")
                return InterfaceBinder(binder, BROKER, proxy)
            }
        }

        // The broker interface itself is obfuscated in GMS, but the request
        // Parcelable is defined under its public name. Parse/rewrite the
        // request with the GMS APK's class and forward a rebuilt transaction.
        val requestClass = load(
            "com.google.android.gms.common.internal.GetServiceRequest",
            gmsClassLoader
        )
        if (requestClass != null) {
            Log.d(TAG, "using Parcel-level GMS transaction bridge")
            return GmsTransactionBinder(binder, requestClass, targetPackage, hostPackage, BROKER)
        }

        Log.w(TAG, "GetServiceRequest not found in GMS class loader")
        return binder
    }

    private fun load(name: String, loader: ClassLoader?): Class<*>? {
        if (loader == null) return null
        return runCatching { Class.forName(name, false, loader) }
            .onFailure { Log.w(TAG, "load $name failed in ${loader.javaClass.name}", it) }
            .getOrNull()
    }

    /**
     * Stub.asInterface(binder) first asks the binder for a local interface.
     * Returning our proxy here makes the client call it directly, while
     * onTransact forwards any raw transaction to the real GMS binder.
     */
    private class InterfaceBinder(
        private val real: IBinder,
        private val descriptor: String,
        private val local: IInterface
    ) : Binder() {

        override fun queryLocalInterface(descriptor: String): IInterface? =
            if (descriptor == this.descriptor) local else null

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            real.transact(code, data, reply, flags)
    }

    private class GmsInvocationHandler(
        private val real: Any,
        private val targetPackage: String,
        private val hostPackage: String
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            args?.let { values ->
                for (i in values.indices) {
                    val value = values[i]
                    if (value is String && value == targetPackage) {
                        values[i] = hostPackage
                    } else {
                        GmsPackageRewrite.rewrite(value, 0, targetPackage, hostPackage)
                    }
                }
            }
            return try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (t: InvocationTargetException) {
                throw t.targetException
            }
        }
    }
}

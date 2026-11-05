package com.coke.nestspace.container.gms

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

private val layoutInspected = AtomicBoolean(false)
private const val TAG = "GmsBroker"

internal class GmsTransactionBinder(
    private val real: IBinder,
    private val requestClass: Class<*>,
    private val targetPackage: String,
    private val hostPackage: String,
    private val brokerToken: String
) : Binder() {

    override fun queryLocalInterface(descriptor: String): IInterface? = null

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val start = data.dataPosition()
        val tokenOffset = findInterfaceToken(data)
        if (tokenOffset >= 0) {
            val handled = runCatching {
                handleBrokerTransaction(code, data, tokenOffset, reply, flags)
            }.onFailure { Log.w(TAG, "broker rewrite failed", it) }
                .getOrDefault(false)
            if (handled) return true
        }
        data.setDataPosition(start)
        return real.transact(code, data, reply, flags)
    }

    /**
     * The WebView GMS client does not write a standard AIDL envelope: its
     * broker parcel is [12-byte header][interface token][IGmsCallbacks
     * binder][GetServiceRequest]. Parse that layout, rewrite the request,
     * and rebuild the envelope around the original callback binder.
     */
    private fun handleBrokerTransaction(
        code: Int,
        data: Parcel,
        tokenOffset: Int,
        reply: Parcel?,
        flags: Int
    ): Boolean {
        val header = IntArray(tokenOffset / 4)
        data.setDataPosition(0)
        for (i in header.indices) header[i] = data.readInt()

        data.setDataPosition(tokenOffset)
        data.readString() ?: return false
        val callbackBinder = data.readStrongBinder() ?: return false
        val binderEnd = data.dataPosition()
        val size = data.dataSize()
        val creator = requestClass.getField("CREATOR").get(null) as Parcelable.Creator<*>

        var request: Parcelable? = null
        var requestStart = -1
        var requestEnd = -1
        for (delta in 0..16 step 4) {
            val candidate = binderEnd + delta
            data.setDataPosition(candidate)
            val parsed = runCatching { creator.createFromParcel(data) }
                .getOrNull() as? Parcelable
            if (parsed != null) {
                request = parsed
                requestStart = candidate
                requestEnd = data.dataPosition()
                break
            }
        }
        val parsedRequest = request ?: run {
            Log.w(TAG, "no request found after binderEnd=$binderEnd size=$size")
            if (layoutInspected.compareAndSet(false, true)) {
                inspectLayout(data, tokenOffset, creator)
            }
            return false
        }
        val gapSize = requestStart - binderEnd
        data.setDataPosition(binderEnd)
        val gap = IntArray(gapSize / 4) { data.readInt() }
        val changed = GmsPackageRewrite.rewrite(parsedRequest, 0, targetPackage, hostPackage)
        Log.d(
            TAG,
            "broker code=$code header=${header.joinToString()} binderEnd=$binderEnd " +
                "gap=${gap.joinToString()} request=$requestStart..$requestEnd changed=$changed"
        )

        val newData = Parcel.obtain()
        try {
            header.forEach { newData.writeInt(it) }
            newData.writeString(brokerToken)
            newData.writeStrongBinder(callbackBinder)
            gap.forEach { newData.writeInt(it) }
            parsedRequest.writeToParcel(newData, 0)
            val hasTarget = containsString(newData, targetPackage)
            val hasHost = containsString(newData, hostPackage)
            Log.d(
                TAG,
                "forward broker code=$code size=${newData.dataSize()} " +
                    "target=$hasTarget host=$hasHost"
            )
            newData.setDataPosition(0)
            return real.transact(code, newData, reply, flags)
        } finally {
            newData.recycle()
        }
    }

    private fun inspectLayout(
        data: Parcel,
        tokenOffset: Int,
        creator: Parcelable.Creator<*>
    ) {
        val size = data.dataSize()
        data.setDataPosition(tokenOffset)
        data.readString()
        val tokenEnd = data.dataPosition()
        data.setDataPosition(tokenEnd)
        val binder = runCatching { data.readStrongBinder() }.getOrNull()
        val binderEnd = data.dataPosition()
        Log.d(TAG, "layout size=$size tokenEnd=$tokenEnd binder=${binder != null} binderEnd=$binderEnd")

        var off = 0
        while (off <= size - 4) {
            data.setDataPosition(off)
            val value = runCatching { data.readString() }.getOrNull()
            if (value != null && value.isNotBlank() && value.length < 100) {
                Log.d(TAG, "layout str@$off=$value")
            }
            off += 4
        }

        val limit = minOf(size, tokenEnd + 96)
        var candidate = tokenEnd
        while (candidate <= limit) {
            data.setDataPosition(candidate)
            val result = runCatching {
                val request = creator.createFromParcel(data)
                "end=${data.dataPosition()} str=${request.toString().take(200)}"
            }.getOrNull()
            if (result != null) Log.d(TAG, "layout request@$candidate $result")
            candidate += 4
        }
    }

    private fun findInterfaceToken(data: Parcel): Int {
        val size = data.dataSize()
        var offset = 0
        while (offset <= minOf(size, 64)) {
            data.setDataPosition(offset)
            val value = runCatching { data.readString() }.getOrNull()
            if (value == brokerToken) return offset
            offset += 4
        }
        return -1
    }

    private fun containsString(parcel: Parcel, value: String): Boolean {
        val size = parcel.dataSize()
        var offset = 0
        while (offset <= size - 4) {
            parcel.setDataPosition(offset)
            if (runCatching { parcel.readString() }.getOrNull() == value) return true
            offset += 4
        }
        return false
    }

    private fun writeInterfaceToken(parcel: Parcel, name: String) {
        val method = runCatching {
            Parcel::class.java.getMethod("writeInterfaceToken", String::class.java)
        }.getOrNull()
        if (method != null) {
            method.invoke(parcel, name)
        } else {
            // Fallback matching Parcel.enforceInterface's readString+readInt
            parcel.writeString(name)
            parcel.writeInt(0)
        }
    }
}

internal object GmsPackageRewrite {
    private const val TAG = "GmsBridge"

    fun rewrite(
        value: Any?,
        depth: Int,
        targetPackage: String,
        hostPackage: String
    ): Boolean {
        if (value == null || depth > 5) return false
        if (value is String || value is IBinder || value is IInterface) return false
        if (value is Collection<*>) {
            var changed = false
            value.forEach {
                if (rewrite(it, depth + 1, targetPackage, hostPackage)) changed = true
            }
            return changed
        }
        val valueClass = value.javaClass
        if (valueClass.isPrimitive || valueClass.isEnum) return false
        if (valueClass.isArray) {
            var changed = false
            for (i in 0 until java.lang.reflect.Array.getLength(value)) {
                if (rewrite(
                        java.lang.reflect.Array.get(value, i),
                        depth + 1,
                        targetPackage,
                        hostPackage
                    )
                ) {
                    changed = true
                }
            }
            return changed
        }

        var changed = false
        var cls: Class<*>? = valueClass
        val visited = mutableSetOf<Class<*>>()
        while (cls != null && cls != Any::class.java && visited.add(cls)) {
            val name = cls.name
            val allowed = name.startsWith("com.google.android.gms") ||
                name == "android.content.AttributionSource" ||
                name == "android.content.AttributionSourceState"
            if (allowed) {
                cls.declaredFields.forEach { field ->
                    runCatching {
                        field.isAccessible = true
                        val fieldValue = field.get(value)
                        if (fieldValue is String && fieldValue == targetPackage) {
                            field.set(value, hostPackage)
                            changed = true
                            Log.d(TAG, "rewrote $name.${field.name} -> $hostPackage")
                        } else if (rewrite(
                                fieldValue,
                                depth + 1,
                                targetPackage,
                                hostPackage
                            )
                        ) {
                            changed = true
                        }
                    }
                }
            }
            cls = cls.superclass
        }
        return changed
    }

}

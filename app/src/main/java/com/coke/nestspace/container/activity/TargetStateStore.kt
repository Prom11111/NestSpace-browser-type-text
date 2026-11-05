package com.coke.nestspace.container.activity

import android.os.Bundle
import com.coke.nestspace.container.ContainerSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps target Activity state out of the host Parcelable Bundle. Target state
 * may contain app-only Parcelable classes the host class loader cannot
 * unmarshal. Entries are consumed when a replacement host creates its target.
 */
internal object TargetStateStore {

    private val states = ConcurrentHashMap<String, Bundle>()
    private val hostIds = java.util.concurrent.atomic.AtomicInteger(1)

    /** A process-unique id for one host Activity instance. */
    fun nextHostId(): Int = hostIds.getAndIncrement()

    fun key(session: ContainerSession, className: String?, hostId: Int): String =
        "${session.id}|${className ?: session.spec.launcherActivity}|$hostId"

    fun consume(key: String): Bundle? = states.remove(key)

    fun put(key: String, state: Bundle) {
        states[key] = state
    }

    fun remove(key: String) {
        states.remove(key)
    }
}

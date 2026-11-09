package com.coke.nestspace.container.runtime

/**
 * One target instance owns a container process. The environment is installed
 * before target Application initialization and remains process-global so
 * Binder, Handler and target-created threads see the same instance identity.
 */
object VirtualEnvironmentRegistry {
    private val lock = Any()

    @Volatile
    private var environment: VirtualEnvironment? = null

    @Volatile
    private var identity = VirtualIdentitySnapshot.uninitialized()

    fun install(candidate: VirtualEnvironment): Boolean = synchronized(lock) {
        val current = environment
        if (current != null) {
            return@synchronized current == candidate
        }
        environment = candidate
        identity = VirtualIdentitySnapshot.uninitialized()
        true
    }

    fun current(): VirtualEnvironment? = environment

    fun identitySnapshot(): VirtualIdentitySnapshot = identity

    fun publishIdentity(
        owner: VirtualEnvironment,
        snapshot: VirtualIdentitySnapshot
    ): Boolean = synchronized(lock) {
        if (environment !== owner || snapshot.revision < identity.revision) {
            return@synchronized false
        }
        identity = snapshot
        true
    }

    fun clear(owner: VirtualEnvironment): Boolean = synchronized(lock) {
        if (environment !== owner) return@synchronized false
        environment = null
        identity = VirtualIdentitySnapshot.uninitialized()
        true
    }

    internal fun resetForTests() {
        synchronized(lock) {
            environment = null
            identity = VirtualIdentitySnapshot.uninitialized()
        }
    }
}

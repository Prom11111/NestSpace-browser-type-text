package com.coke.nestspace.container.identity

import android.os.Process

/**
 * UID model for virtual apps.
 *
 * The container process always runs under the host UID, and the system side
 * only knows that UID. Self-queries therefore report the real UID so target
 * checks that compare `Process.myUid()` with PackageManager answers stay
 * consistent. Packages installed in the same space but not currently running
 * get a stable synthetic UID, exactly like separately installed apps would,
 * so cross-virtual-app UID queries do not collapse onto the host UID.
 *
 * This is app-facing identity only (Java layer). It does not spoof the UID the
 * kernel puts into Binder transactions; system services still see the host
 * UID. Package/attribution arguments are rewritten by SystemServiceIdentity
 * before they reach the system.
 */
object ContainerIdentity {

    /** Above the per-user app UID range (10000-19999) used by the system. */
    private const val VIRTUAL_UID_BASE = 70_000
    private const val VIRTUAL_UID_RANGE = 20_000

    fun hostUid(): Int = Process.myUid()

    /** UserHandle.myUserId() is hidden; the container runs in the current user. */
    fun userId(): Int = runCatching {
        Class.forName("android.os.UserHandle")
            .getMethod("myUserId")
            .invoke(null) as Int
    }.getOrDefault(0)

    /** Stable synthetic UID for a package that is installed in the space. */
    fun virtualUidFor(packageName: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (ch in packageName) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        val value = hash and Int.MAX_VALUE
        return VIRTUAL_UID_BASE + (value % VIRTUAL_UID_RANGE)
    }

    fun isVirtualUid(uid: Int): Boolean =
        uid in VIRTUAL_UID_BASE until (VIRTUAL_UID_BASE + VIRTUAL_UID_RANGE)
}

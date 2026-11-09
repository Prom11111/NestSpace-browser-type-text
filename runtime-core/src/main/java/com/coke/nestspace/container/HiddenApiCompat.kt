package com.coke.nestspace.container

import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Stage B uses a small set of hidden framework APIs to attach the target
 * Activity. Android 9+ enforces hidden API restrictions; exempt all hidden
 * members before touching Activity.attach on newer devices.
 */
object HiddenApiCompat {

    @Volatile
    private var ensured = false

    @Synchronized
    fun ensure(force: Boolean = false) {
        if (ensured && !force) return
        // Hidden API enforcement starts with Android 9 (API 28). Older
        // releases (including API 25 x86 emulators) can crash inside
        // HiddenApiBypass even though no exemption is needed there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("L")
            }.onFailure {
                Log.w("HiddenApiCompat", "hidden API exemption failed", it)
            }
        }
        ensured = true
    }
}

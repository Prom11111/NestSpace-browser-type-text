package com.coke.nestspace.location

import android.annotation.SuppressLint
import android.content.Context
import com.coke.nestspace.data.AppSettingsEvents
import java.util.Locale

/**
 * Simulated coordinates exposed to virtual apps.
 *
 * The setting lives in the main process, while each virtual app runs in its
 * own container slot process. [load] therefore reads with
 * [Context.MODE_MULTI_PROCESS] so a container picks up a saved coordinate
 * without having to restart the virtual app.
 */
object MockLocationSettings {

    data class Config(
        val enabled: Boolean = false,
        val latitude: Double = DEFAULT_LATITUDE,
        val longitude: Double = DEFAULT_LONGITUDE
    )

    private const val PREFS = "mock_location"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LATITUDE = "latitude"
    private const val KEY_LONGITUDE = "longitude"

    /** A neutral starting point used until the user enters coordinates. */
    const val DEFAULT_LATITUDE = 39.908800
    const val DEFAULT_LONGITUDE = 116.397500

    /** Reported horizontal/vertical accuracy of the simulated fix. */
    const val ACCURACY_METERS = 5f

    @Suppress("DEPRECATION")
    fun load(context: Context): Config {
        val prefs = prefs(context)
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            latitude = prefs.getString(KEY_LATITUDE, null)
                ?.let(::parseLatitude)
                ?: DEFAULT_LATITUDE,
            longitude = prefs.getString(KEY_LONGITUDE, null)
                ?.let(::parseLongitude)
                ?: DEFAULT_LONGITUDE
        )
    }

    // commit() flushes the file immediately: a container slot process may read
    // it right after the user taps save.
    @SuppressLint("ApplySharedPref")
    fun save(context: Context, config: Config) {
        val latitude = config.latitude.takeIf(::isValidLatitude) ?: DEFAULT_LATITUDE
        val longitude = config.longitude.takeIf(::isValidLongitude) ?: DEFAULT_LONGITUDE
        prefs(context)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_LATITUDE, latitude.toString())
            .putString(KEY_LONGITUDE, longitude.toString())
            .commit()
        AppSettingsEvents.notifyChanged()
    }

    /** Empty when disabled or when the coordinates are not usable. */
    fun label(context: Context): String = label(load(context))

    fun label(config: Config): String = if (!config.enabled) {
        ""
    } else {
        format(config.latitude) + ", " + format(config.longitude)
    }

    fun parseLatitude(value: String): Double? =
        value.trim().toDoubleOrNull()?.takeIf(::isValidLatitude)

    fun parseLongitude(value: String): Double? =
        value.trim().toDoubleOrNull()?.takeIf(::isValidLongitude)

    fun format(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun isValidLatitude(value: Double): Boolean =
        value.isFinite() && value in -90.0..90.0

    private fun isValidLongitude(value: Double): Boolean =
        value.isFinite() && value in -180.0..180.0

    @Suppress("DEPRECATION")
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)
}

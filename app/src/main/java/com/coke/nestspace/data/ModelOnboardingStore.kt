package com.coke.nestspace.data

import android.content.Context

/** Persists whether the one-time model setup guide has been shown. */
class ModelOnboardingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isCompleted(): Boolean = prefs.getBoolean(KEY_COMPLETED, false)

    fun markCompleted() {
        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    companion object {
        private const val PREFS = "model_onboarding"
        private const val KEY_COMPLETED = "completed"
    }
}

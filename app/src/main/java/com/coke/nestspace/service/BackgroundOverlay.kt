package com.coke.nestspace.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * A 1x1, non-focusable, non-touchable overlay.
 *
 * Android blocks background activity starts (BAL) when the app has no visible
 * window. Holding a SYSTEM_ALERT_WINDOW overlay exempts the app, which lets the
 * MCP server launch virtual apps headlessly after boot without opening the UI.
 */
object BackgroundOverlay {

    private const val TAG = "BackgroundOverlay"
    private var view: View? = null

    fun ensure(context: Context) {
        if (view != null) return
        if (!Settings.canDrawOverlays(context)) return

        // This overlay is process-scoped; never retain an Activity context.
        val app = context.applicationContext
        val windowManager = app.getSystemService(WindowManager::class.java) ?: return
        val overlay = View(app)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
        }
        runCatching {
            windowManager.addView(overlay, params)
            view = overlay
        }.onFailure { Log.w(TAG, "overlay add failed", it) }
    }

    fun remove(context: Context) {
        val overlay = view ?: return
        view = null
        val app = context.applicationContext
        val windowManager = app.getSystemService(WindowManager::class.java) ?: return
        runCatching { windowManager.removeView(overlay) }
            .onFailure { Log.w(TAG, "overlay remove failed", it) }
    }
}

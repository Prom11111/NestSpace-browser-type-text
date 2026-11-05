package com.coke.nestspace.container

import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A small on-screen window backed by a full-resolution VirtualDisplay.
 *
 * The target Activity is attached to a Presentation on this display, so all
 * of its surfaces (including SurfaceView/WebView) are composited at the real
 * device resolution. The SurfaceView only scales that finished frame down;
 * this is a live mirror, not a screenshot or screen recording.
 */
object HeadlessMirrorWindow {

    private const val TAG = "HeadlessMirror"
    private const val NAME = "NestSpace mirror"
    private const val INVALID = Int.MIN_VALUE

    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var surfaceView: SurfaceView? = null
    private var windowManager: WindowManager? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displayId: Int = INVALID
    private var ready: CompletableDeferred<Int?>? = null

    suspend fun awaitDisplay(context: Context): Int? = withContext(Dispatchers.Main) {
        if (displayId != INVALID) return@withContext displayId
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "overlay permission missing")
            return@withContext null
        }
        ensureWindow(app)
        if (displayId != INVALID) return@withContext displayId
        val id = ready?.let { withTimeoutOrNull(5_000L) { it.await() } }
        if (id == null) hide()
        id
    }

    fun hide() {
        main.post {
            virtualDisplay?.release()
            virtualDisplay = null
            displayId = INVALID
            surfaceView = null
            overlay?.let { view ->
                runCatching { windowManager?.removeView(view) }
            }
            overlay = null
            windowManager = null
            ready = null
        }
    }

    private fun ensureWindow(context: Context) {
        if (overlay != null) return
        val manager = context.getSystemService(WindowManager::class.java) ?: return
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val width = (200 * density).toInt()
        // Keep the overlay aspect ratio identical to the mirrored display;
        // otherwise SurfaceFlinger scales/crops the full-resolution frame and
        // only a corner of the target is visible.
        val height = (width.toFloat() * metrics.heightPixels / metrics.widthPixels).toInt()

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }
        val surface = SurfaceView(context)
        surface.holder.setFixedSize(metrics.widthPixels, metrics.heightPixels)
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                createDisplay(context, holder)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                virtualDisplay?.release()
                virtualDisplay = null
                displayId = INVALID
            }
        })

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = (12 * density).toInt()
            y = (96 * density).toInt()
        }
        ready = CompletableDeferred()
        val added = runCatching { manager.addView(root, params) }.isSuccess
        if (!added) {
            ready?.complete(null)
            ready = null
            return
        }
        overlay = root
        surfaceView = surface
        windowManager = manager
    }

    private fun createDisplay(context: Context, holder: SurfaceHolder) {
        if (virtualDisplay != null) {
            ready?.complete(displayId)
            return
        }
        val manager = context.getSystemService(DisplayManager::class.java) ?: return
        val metrics = context.resources.displayMetrics
        val display = runCatching {
            manager.createVirtualDisplay(
                NAME,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                holder.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
        }.onFailure {
            Log.w(TAG, "createVirtualDisplay failed", it)
        }.getOrNull()
        if (display == null) {
            ready?.complete(null)
            return
        }
        virtualDisplay = display
        displayId = display.display.displayId
        Log.i(TAG, "display created id=$displayId surface=${holder.surface}")
        ready?.complete(displayId)
    }
}

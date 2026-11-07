package com.coke.nestspace.container

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A small on-screen window backed by a full-resolution VirtualDisplay.
 *
 * The target Activity is attached to a Presentation on this display, so all
 * of its surfaces (including SurfaceView/WebView) are composited at the real
 * device resolution. The SurfaceView only scales that finished frame down;
 * this is a live mirror, not a screenshot or screen recording.
 *
 * The mirror is an application panel owned by the AI assistant page. It is
 * never promoted to a system overlay and disappears with that page.
 */
object HeadlessMirrorWindow {

    private const val TAG = "HeadlessMirror"
    private const val NAME = "NestSpace mirror"
    private const val INVALID = Int.MIN_VALUE
    private const val DISPLAY_FILE = "headless_mirror_display"

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var hostActivity = WeakReference<Activity>(null)
    private var overlay: View? = null
    private var surfaceView: SurfaceView? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displayId: Int = INVALID
    private var ready: CompletableDeferred<Int?>? = null
    @Volatile
    private var hiding = false
    @Volatile
    private var hostResumed = false
    @Volatile
    private var assistantVisible = false
    @Volatile
    private var activeSlot: Int = -1
    private val backListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    fun bind(activity: Activity) {
        hostActivity = WeakReference(activity)
    }

    fun unbind(activity: Activity) {
        if (hostActivity.get() === activity) {
            hide()
            hostActivity = WeakReference(null)
            hostResumed = false
        }
    }

    fun onHostResumed(activity: Activity) {
        if (hostActivity.get() === activity) hostResumed = true
    }

    fun onHostPaused(activity: Activity) {
        if (hostActivity.get() === activity) hostResumed = false
    }

    fun setAssistantVisible(visible: Boolean) {
        assistantVisible = visible
        if (!visible) hide()
    }

    /**
     * Remember which container slot is shown in the mirror. The host Activity
     * runs in a container process behind the assistant, so back pressed while
     * the assistant is foreground has to be routed to that slot explicitly.
     */
    fun setActiveSlot(slot: Int) {
        activeSlot = slot
        publishBackEnabled()
    }

    fun activeSlot(): Int = activeSlot

    fun isBackEnabled(): Boolean = backEnabled()

    /** Notified with true while a live mirror with a known slot is on screen. */
    fun addBackEnabledListener(listener: (Boolean) -> Unit) {
        backListeners.add(listener)
        main.post { listener(backEnabled()) }
    }

    fun removeBackEnabledListener(listener: (Boolean) -> Unit) {
        backListeners.remove(listener)
    }

    private fun backEnabled(): Boolean = activeSlot >= 0 && displayId != INVALID

    private fun publishBackEnabled() {
        val enabled = backEnabled()
        main.post { backListeners.forEach { it(enabled) } }
    }

    suspend fun awaitDisplay(context: Context): Int? = withContext(Dispatchers.Main) {
        while (hiding) kotlinx.coroutines.delay(10)
        if (displayId != INVALID) return@withContext displayId
        if (!assistantVisible || !hostResumed || hostActivity.get() == null) {
            Log.w(TAG, "mirror hidden: assistant page is not visible")
            return@withContext null
        }
        ensureWindow(context)
        if (displayId != INVALID) return@withContext displayId
        val id = ready?.let { withTimeoutOrNull(5_000L) { it.await() } }
        if (id == null) {
            Log.w(TAG, "mirror surface was not ready; falling back")
            hide()
            return@withContext null
        }
        // SurfaceView can replace its surface once while the panel settles
        // into its final window position. Let that happen before the host
        // launch captures the display id.
        kotlinx.coroutines.delay(700)
        displayId.takeIf { it != INVALID } ?: run {
            Log.w(TAG, "mirror display disappeared during settle")
            hide()
            null
        }
    }

    /**
     * Display id of a live mirror, readable from container processes. Child
     * hosts launched by the target must join the existing mirror instead of
     * creating a second small window.
     */
    fun storedDisplayId(context: Context): Int {
        val app = context.applicationContext
        appContext = app
        // A plain file is visible across the main and container processes
        // immediately; SharedPreferences caches its values per process.
        return runCatching {
            File(app.filesDir, DISPLAY_FILE).readText().trim().toInt()
        }.getOrDefault(INVALID)
    }

    fun hide() {
        if (hiding) return
        hiding = true
        val remove: () -> Unit = {
            try {
                virtualDisplay?.release()
                virtualDisplay = null
                displayId = INVALID
                activeSlot = -1
                publishBackEnabled()
                surfaceView = null
                overlay?.let { view ->
                    runCatching { windowManager?.removeView(view) }
                }
                overlay = null
                params = null
                windowManager = null
                ready = null
                appContext?.let { clearDisplayFile(it) }
            } finally {
                hiding = false
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            remove()
        } else {
            main.post(remove)
        }
    }

    private fun ensureWindow(context: Context) {
        if (overlay != null) return
        appContext = context.applicationContext
        val activity = hostActivity.get()?.takeIf { hostResumed && assistantVisible }
            ?: return
        val host = activity
        val manager = host.getSystemService(WindowManager::class.java) ?: return
        val metrics = host.resources.displayMetrics
        val density = metrics.density
        // Match the old system PiP window footprint (~300 px on a 1080 px
        // display) instead of the larger 200 dp mirror panel.
        val width = (115 * density).toInt()
        val height = (width.toFloat() * metrics.heightPixels / metrics.widthPixels).toInt()
        val margin = (12 * density).toInt()

        val root = FrameLayout(host).apply { setBackgroundColor(Color.BLACK) }
        val surface = SurfaceView(host)
        surface.holder.setFixedSize(metrics.widthPixels, metrics.heightPixels)
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val header = FrameLayout(host).apply {
            setBackgroundColor(Color.argb(165, 0, 0, 0))
            isClickable = true
        }
        val title = TextView(host).apply {
            text = "NestSpace · 镜像"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * density).toInt(), 0, 0, 0)
        }
        val close = TextView(host).apply {
            text = "×"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, (10 * density).toInt(), 0)
            setOnClickListener { closeAndStopTarget() }
        }
        header.addView(
            title,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        header.addView(
            close,
            FrameLayout.LayoutParams(
                (40 * density).toInt(),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END
            )
        )
        root.addView(
            header,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (30 * density).toInt(),
                Gravity.TOP
            )
        )

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (surfaceView !== surface || overlay == null) return
                createDisplay(host, holder)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // A removed window's SurfaceView can deliver this after a new
                // mirror window has already been created. Only the current
                // surface may tear the display down.
                if (surfaceView !== surface) return
                releaseDisplay()
            }
        })

        val layoutParams = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            token = activity.window?.decorView?.windowToken
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - width - margin
            y = metrics.heightPixels - height - (72 * density).toInt()
        }

        // Publish the new surface before addView: surfaceCreated can be
        // delivered during addView, and the callback must already be able to
        // recognize this window as current.
        ready = CompletableDeferred()
        overlay = root
        surfaceView = surface
        windowManager = manager
        params = layoutParams

        val installed = runCatching { manager.addView(root, layoutParams) }.isSuccess
        if (!installed) {
            Log.w(TAG, "mirror panel could not be added")
            overlay = null
            surfaceView = null
            windowManager = null
            params = null
            ready?.complete(null)
            ready = null
            return
        }
        Log.i(TAG, "mirror panel added")

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        title.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = (startX + event.rawX - downRawX)
                        .toInt()
                        .coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
                    layoutParams.y = (startY + event.rawY - downRawY)
                        .toInt()
                        .coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
                    runCatching { manager.updateViewLayout(root, layoutParams) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /**
     * The title-bar close button means "close this app", not merely hide its
     * preview. Keeping the target process alive would leave video apps playing
     * audio with no visible window.
     */
    private fun closeAndStopTarget() {
        val slot = activeSlot
        val context = appContext
        hide()
        if (slot < 0 || context == null) return
        Thread(
            {
                val closed = runCatching {
                    ContainerProcessPool.closeSlot(context, slot)
                }.onFailure {
                    Log.w(TAG, "failed to close slot $slot", it)
                }.getOrDefault(false)
                Log.d(TAG, "close button stopped slot $slot: $closed")
            },
            "NestSpace-mirror-close"
        ).start()
    }

    private fun createDisplay(context: Context, holder: SurfaceHolder) {
        if (surfaceView?.holder !== holder) return
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
        appContext?.let { writeDisplayFile(it, displayId) }
        Log.i(TAG, "display created id=$displayId surface=${holder.surface}")
        publishBackEnabled()
        ready?.complete(displayId)
    }

    private fun releaseDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        displayId = INVALID
        activeSlot = -1
        appContext?.let { clearDisplayFile(it) }
        publishBackEnabled()
    }

    private fun writeDisplayFile(context: Context, id: Int) {
        runCatching {
            File(context.filesDir, DISPLAY_FILE).writeText(id.toString())
        }.onFailure { Log.w(TAG, "write mirror id failed", it) }
    }

    private fun clearDisplayFile(context: Context) {
        runCatching { File(context.filesDir, DISPLAY_FILE).delete() }
    }
}

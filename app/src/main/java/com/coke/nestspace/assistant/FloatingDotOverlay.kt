package com.coke.nestspace.assistant

import com.coke.nestspace.i18n.AppLanguage
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import com.coke.nestspace.container.identity.HostIdentity
import kotlin.math.abs
import kotlin.math.min

/**
 * iPhone assistive-touch style AI dot.
 *
 * Lives in the main process as a system overlay so it stays visible over both
 * the NestSpace desktop and virtual-app container windows. The color follows
 * the surface underneath: host screens use hostColor, containers use
 * containerColor. Tapping opens AssistantVoiceActivity.
 */
object FloatingDotOverlay {

    private const val TAG = "FloatingDot"
    const val ACTION_MODE = "com.coke.nestspace.FLOATING_DOT_MODE"
    const val EXTRA_CONTAINER = "container"

    private var windowManager: WindowManager? = null
    private var dotView: DotView? = null
    private var params: WindowManager.LayoutParams? = null
    private var containerMode = false
    /** Reasons currently showing a container-owned host surface (desktop, spaces). */
    private val containerSurfaces =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var suppressed = false
    private var assistantPageVisible = false
    private val overlayReasons = mutableSetOf<String>()
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingHostMode: Runnable? = null
    private var modeReceiver: BroadcastReceiver? = null

    fun ensure(context: Context) {
        val app = context.applicationContext
        appContext = app
        val config = FloatingDotSettings.load(app)
        if (!config.enabled || !Settings.canDrawOverlays(app)) {
            removeView()
            return
        }
        registerModeReceiver(app)
        if (dotView == null) {
            createView(app, config)
        } else {
            applyColor(config)
            clampToCurrentBounds(app)
        }
    }

    fun reload(context: Context) {
        ensure(context)
    }

    fun setSuppressed(context: Context, value: Boolean) {
        suppressed = value
        applyVisibility()
        if (!value) ensure(context)
    }

    /**
     * The in-app AI assistant page hides the dot: it has its own conversation
     * UI and the dot would otherwise overlap the input/keyboard. Kept separate
     * from [setSuppressed] so the voice Activity and the assistant tab can be
     * hidden independently without one clearing the other's state.
     */
    fun setAssistantPageVisible(context: Context, visible: Boolean) {
        assistantPageVisible = visible
        applyVisibility()
        if (!visible) ensure(context)
    }

    /**
     * Generic suppression for host UI that should not be covered, such as an
     * open desktop menu or folder sheet. Reasons are independent so closing a
     * menu doesn't override the voice Activity's suppression (and vice versa).
     */
    fun setOverlayHidden(context: Context, reason: String, hidden: Boolean) {
        if (hidden) overlayReasons.add(reason) else overlayReasons.remove(reason)
        applyVisibility()
        if (!hidden) ensure(context)
    }

    private fun isHidden(): Boolean =
        suppressed || assistantPageVisible || overlayReasons.isNotEmpty()

    private fun applyVisibility() {
        dotView?.visibility = if (isHidden()) View.GONE else View.VISIBLE
    }

    fun currentContainerMode(): Boolean =
        containerMode || containerSurfaces.isNotEmpty()

    fun currentColor(context: Context): Int {
        val config = FloatingDotSettings.load(context)
        return if (currentContainerMode()) config.containerColor else config.hostColor
    }

    /**
     * The NestSpace desktop is the container's home surface: it should use the
     * container color even when no virtual app Activity is open. Host pages
     * (settings, system apps) keep the host color; the built-in browser is
     * container-owned and therefore keeps the container color too.
     */
    fun setContainerDesktopVisible(context: Context, visible: Boolean) {
        setContainerSurfaceVisible(context, "desktop", visible)
    }

    /**
     * Keep the container color while a container-owned host surface is on
     * screen. Uses independent reasons so the desktop and the Spaces tab
     * cannot clear each other during a tab switch.
     */
    fun setContainerSurfaceVisible(
        context: Context,
        reason: String,
        visible: Boolean
    ) {
        if (visible) containerSurfaces.add(reason) else containerSurfaces.remove(reason)
        ensure(context)
        applyColor(FloatingDotSettings.load(context))
    }

    /** Host activity resumed: reset container mode; desktop/spaces re-apply it. */
    fun onHostActivityResumed(context: Context) {
        cancelPendingHostMode()
        containerMode = false
        ensure(context)
        applyColor(FloatingDotSettings.load(context))
    }

    /**
     * Container host Activity resumed/paused. False is debounced so switching
     * between two container Activities does not flicker host color.
     */
    fun onModeBroadcast(context: Context, intent: Intent) {
        val container = intent.getBooleanExtra(EXTRA_CONTAINER, false)
        ensure(context)
        if (container) {
            cancelPendingHostMode()
            containerMode = true
            // Entering a container means the assistant page is no longer the
            // visible surface, even if its lifecycle event has not arrived yet.
            assistantPageVisible = false
            applyColor(FloatingDotSettings.load(context))
        } else {
            cancelPendingHostMode()
            val runnable = Runnable {
                pendingHostMode = null
                containerMode = false
                applyColor(FloatingDotSettings.load(context))
            }
            pendingHostMode = runnable
            handler.postDelayed(runnable, HOST_MODE_DELAY_MS)
        }
    }

    fun publishContainerMode(context: Context, container: Boolean) {
        // StageAActivity's package name is the virtual target, not NestSpace.
        val intent = Intent(ACTION_MODE)
            .setPackage(HostIdentity.packageName)
            .putExtra(EXTRA_CONTAINER, container)
        context.sendBroadcast(intent)
    }

    private fun registerModeReceiver(app: Context) {
        if (modeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent != null) onModeBroadcast(context?.applicationContext ?: app, intent)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(
                    receiver,
                    android.content.IntentFilter(ACTION_MODE),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, android.content.IntentFilter(ACTION_MODE))
            }
            modeReceiver = receiver
        }.onFailure { Log.w(TAG, "mode receiver register failed", it) }
    }

    private fun unregisterModeReceiver(app: Context) {
        val receiver = modeReceiver ?: return
        modeReceiver = null
        runCatching { app.unregisterReceiver(receiver) }
    }

    private fun cancelPendingHostMode() {
        pendingHostMode?.let { handler.removeCallbacks(it) }
        pendingHostMode = null
    }

    private fun applyColor(config: FloatingDotSettings.Config) {
        dotView?.let { view ->
            view.dotColor = if (currentContainerMode()) config.containerColor else config.hostColor
            view.alpha = config.opacity / 100f
            view.visibility = if (isHidden()) View.GONE else View.VISIBLE
        }
    }

    private fun createView(app: Context, config: FloatingDotSettings.Config) {
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        val density = app.resources.displayMetrics.density
        val size = (64 * density).toInt()
        val margin = (10 * density).toInt()
        val bounds = windowBounds(app, wm)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val initialX = if (config.x >= 0) config.x else bounds.width() - size - margin
        val initialY = if (config.y >= 0) config.y else (bounds.height() * 0.62f).toInt()
        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }
        clamp(layoutParams, bounds, size, margin)

        val view = DotView(app).apply {
            dotColor = if (currentContainerMode()) config.containerColor else config.hostColor
            alpha = config.opacity / 100f
            visibility = if (isHidden()) View.GONE else View.VISIBLE
            onTap = { openAssistant(app) }
            onDrag = { dx, dy ->
                val currentBounds = windowBounds(app, wm)
                layoutParams.x = (layoutParams.x + dx).toInt()
                layoutParams.y = (layoutParams.y + dy).toInt()
                clamp(layoutParams, currentBounds, size, margin)
                runCatching { wm.updateViewLayout(this, layoutParams) }
            }
            onDragEnd = {
                val currentBounds = windowBounds(app, wm)
                val left = margin
                val right = currentBounds.width() - size - margin
                layoutParams.x = if (layoutParams.x + size / 2 < currentBounds.width() / 2) {
                    left
                } else {
                    right
                }
                clamp(layoutParams, currentBounds, size, margin)
                runCatching { wm.updateViewLayout(this, layoutParams) }
                FloatingDotSettings.savePosition(app, layoutParams.x, layoutParams.y)
            }
        }

        val added = runCatching {
            wm.addView(view, layoutParams)
            true
        }.onFailure { Log.w(TAG, "floating dot overlay failed", it) }.getOrDefault(false)
        if (!added) return

        windowManager = wm
        dotView = view
        params = layoutParams
    }

    private fun clampToCurrentBounds(app: Context) {
        val wm = windowManager ?: return
        val view = dotView ?: return
        val layoutParams = params ?: return
        val density = app.resources.displayMetrics.density
        val margin = (10 * density).toInt()
        clamp(layoutParams, windowBounds(app, wm), layoutParams.width, margin)
        runCatching { wm.updateViewLayout(view, layoutParams) }
    }

    private fun windowBounds(app: Context, wm: WindowManager): android.graphics.Rect =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.bounds
            } else {
                android.graphics.Rect(
                    0,
                    0,
                    app.resources.displayMetrics.widthPixels,
                    app.resources.displayMetrics.heightPixels
                )
            }
        }.getOrElse {
            android.graphics.Rect(
                0,
                0,
                app.resources.displayMetrics.widthPixels,
                app.resources.displayMetrics.heightPixels
            )
        }

    private fun clamp(
        layoutParams: WindowManager.LayoutParams,
        bounds: android.graphics.Rect,
        size: Int,
        margin: Int
    ) {
        val minX = margin
        val maxX = maxOf(minX, bounds.width() - size - margin)
        val minY = (64 * (size / 64f)).toInt()
        val maxY = maxOf(minY, bounds.height() - size - (100 * (size / 64f)).toInt())
        layoutParams.x = layoutParams.x.coerceIn(minX, maxX)
        layoutParams.y = layoutParams.y.coerceIn(minY, maxY)
    }

    private fun removeView() {
        appContext?.let { unregisterModeReceiver(it) }
        val view = dotView ?: return
        dotView = null
        params = null
        view.animate().cancel()
        runCatching { windowManager?.removeView(view) }
        windowManager = null
    }

    private fun openAssistant(app: Context) {
        val intent = Intent(app, AssistantVoiceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
            .onFailure { Log.w(TAG, "open assistant failed", it) }
    }

    private const val HOST_MODE_DELAY_MS = 350L

    /** Manifest receiver: also starts the main process if it was killed. */
    class ModeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onModeBroadcast(context.applicationContext, intent)
        }
    }

    private class DotView(context: Context) : View(context) {

        var dotColor: Int = FloatingDotSettings.DEFAULT_HOST_COLOR
            set(value) {
                field = value
                invalidate()
            }

        var onTap: (() -> Unit)? = null
        var onDrag: ((Float, Float) -> Unit)? = null
        var onDragEnd: (() -> Unit)? = null

        private val density = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var dragging = false

        init {
            isClickable = true
            contentDescription = AppLanguage.tr("AI 助手语音", "AI assistant voice")
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    if (!dragging &&
                        (abs(event.rawX - downRawX) > touchSlop ||
                            abs(event.rawY - downRawY) > touchSlop)
                    ) {
                        dragging = true
                    }
                    if (dragging) onDrag?.invoke(dx, dy)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (dragging) {
                        onDragEnd?.invoke()
                    } else {
                        performClick()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (dragging) onDragEnd?.invoke()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            onTap?.invoke()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f - 3f * density

            glowPaint.color = ColorUtils.setAlphaComponent(dotColor, 60)
            canvas.drawCircle(cx, cy, radius * 1.16f, glowPaint)

            fillPaint.shader = RadialGradient(
                cx - radius * 0.35f,
                cy - radius * 0.35f,
                radius * 1.7f,
                intArrayOf(
                    ColorUtils.blendARGB(dotColor, Color.WHITE, 0.30f),
                    dotColor,
                    ColorUtils.blendARGB(dotColor, Color.BLACK, 0.18f)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, fillPaint)
            fillPaint.shader = null

            iconPaint.strokeWidth = radius * 0.075f
            val micWidth = radius * 0.30f
            val bodyTop = cy - radius * 0.36f
            val bodyBottom = cy + radius * 0.10f
            canvas.drawRoundRect(
                RectF(
                    cx - micWidth / 2f,
                    bodyTop,
                    cx + micWidth / 2f,
                    bodyBottom
                ),
                micWidth / 2f,
                micWidth / 2f,
                iconPaint
            )
            canvas.drawArc(
                RectF(
                    cx - radius * 0.23f,
                    cy - radius * 0.10f,
                    cx + radius * 0.23f,
                    cy + radius * 0.26f
                ),
                0f,
                180f,
                false,
                iconPaint
            )
            canvas.drawLine(cx, cy + radius * 0.26f, cx, cy + radius * 0.40f, iconPaint)
            canvas.drawLine(
                cx - radius * 0.13f,
                cy + radius * 0.40f,
                cx + radius * 0.13f,
                cy + radius * 0.40f,
                iconPaint
            )
        }
    }
}

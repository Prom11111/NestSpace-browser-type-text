package com.coke.nestspace.container

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Branded launch surface with a dedicated render thread.
 *
 * Target Application startup can block Android's main thread for several
 * seconds, which freezes ordinary View animations. SurfaceView lets the
 * loading indicator keep moving independently while the app is being loaded.
 */
internal class LaunchLoadingView(
    context: Context,
    appLabel: String?
) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private companion object {
        const val FRAME_MS = 16L
        const val CYCLE_MS = 2_600L
    }

    private val density = resources.displayMetrics.density
    private val displayLabel = appLabel?.trim().orEmpty()
    private val title = if (displayLabel.isBlank()) {
        AppLanguage.tr("正在加载应用…", "Loading app…")
    } else {
        AppLanguage.tr("正在加载 $displayLabel…", "Loading $displayLabel…")
    }
    private val statuses = listOf(
        AppLanguage.tr("正在准备运行环境", "Preparing the runtime"),
        AppLanguage.tr("正在载入应用资源", "Loading app resources"),
        AppLanguage.tr("即将完成", "Almost ready")
    )
    private val avatarBitmap: Bitmap? = runCatching {
        BitmapFactory.decodeResource(resources, R.drawable.ai_assistant_cutout)
    }.getOrNull()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val avatarRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 4f * density
    }
    private val avatarShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 49, 62, 135)
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avatarMatrix = Matrix()
    private var avatarShader: BitmapShader? = null
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(28, 30, 42)
        textAlign = Paint.Align.CENTER
        textSize = 23f * density
        typeface = android.graphics.Typeface.create(
            "sans-serif",
            android.graphics.Typeface.BOLD
        )
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(103, 108, 128)
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
    }
    private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(92, 105, 179)
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
        letterSpacing = 0.14f
        typeface = android.graphics.Typeface.create(
            "sans-serif-medium",
            android.graphics.Typeface.NORMAL
        )
    }
    private val badgeLetterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 18f * density
        typeface = android.graphics.Typeface.create(
            "sans-serif",
            android.graphics.Typeface.BOLD
        )
    }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 75, 92, 180)
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 102, 220)
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val renderLock = Any()
    @Volatile
    private var rendering = false
    private var renderThread: Thread? = null

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.OPAQUE)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startRendering()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRendering()
    }

    override fun onDetachedFromWindow() {
        stopRendering()
        super.onDetachedFromWindow()
    }

    private fun startRendering() {
        synchronized(renderLock) {
            if (rendering) return
            rendering = true
            renderThread = Thread(this, "nestspace-launch-render").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun stopRendering() {
        val thread = synchronized(renderLock) {
            if (!rendering) return
            rendering = false
            renderThread.also { renderThread = null }
        }
        thread?.interrupt()
    }

    override fun run() {
        while (rendering) {
            val started = SystemClock.uptimeMillis()
            val canvas = runCatching { holder.lockCanvas() }.getOrNull()
            if (canvas != null) {
                try {
                    drawFrame(canvas, started)
                } finally {
                    runCatching { holder.unlockCanvasAndPost(canvas) }
                }
            }
            val elapsed = SystemClock.uptimeMillis() - started
            val wait = (FRAME_MS - elapsed).coerceAtLeast(1L)
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun drawFrame(canvas: Canvas, now: Long) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (width <= 0f || height <= 0f) return
        val phase = (now % CYCLE_MS).toFloat() / CYCLE_MS

        backgroundPaint.shader = LinearGradient(
            0f,
            0f,
            width,
            height,
            intArrayOf(
                Color.rgb(249, 250, 255),
                Color.rgb(242, 246, 255),
                Color.rgb(249, 250, 253)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)

        val centerX = width / 2f
        val centerY = height * 0.43f
        val breathe = 1f + 0.045f * sin(phase * PI.toFloat() * 2f)
        val avatarRadius = 56f * density

        haloPaint.shader = RadialGradient(
            centerX,
            centerY,
            130f * density * breathe,
            intArrayOf(
                Color.argb(55, 91, 116, 228),
                Color.argb(16, 91, 116, 228),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, 130f * density * breathe, haloPaint)

        orbitPaint.style = Paint.Style.STROKE
        orbitPaint.strokeWidth = 1.2f * density
        orbitPaint.color = Color.argb(45, 76, 102, 220)
        canvas.drawCircle(centerX, centerY, 74f * density, orbitPaint)

        repeat(3) { index ->
            val angle = phase * PI.toFloat() * 2f + index * PI.toFloat() * 2f / 3f
            val radius = (72f + index * 4f) * density
            val dotX = centerX + cos(angle) * radius
            val dotY = centerY + sin(angle) * radius
            orbitPaint.style = Paint.Style.FILL
            orbitPaint.color = Color.argb(
                if (index == 0) 235 else 150,
                76,
                102,
                220
            )
            canvas.drawCircle(
                dotX,
                dotY,
                (if (index == 0) 4.2f else 2.8f) * density,
                orbitPaint
            )
        }

        val avatarScale = 1f + 0.012f * sin(phase * PI.toFloat() * 2f)
        canvas.save()
        canvas.scale(avatarScale, avatarScale, centerX, centerY)
        canvas.drawCircle(
            centerX,
            centerY + 3f * density,
            avatarRadius + 11f * density,
            avatarShadowPaint
        )
        avatarRingPaint.strokeWidth = 4f * density
        canvas.drawCircle(centerX, centerY, avatarRadius + 6f * density, avatarRingPaint)
        val avatar = avatarBitmap
        if (avatar != null) {
            val shader = avatarShader ?: BitmapShader(
                avatar,
                Shader.TileMode.CLAMP,
                Shader.TileMode.CLAMP
            ).also { avatarShader = it }
            val scale = (avatarRadius * 2f) / minOf(avatar.width, avatar.height)
            avatarMatrix.reset()
            avatarMatrix.setScale(scale, scale)
            avatarMatrix.postTranslate(
                centerX - avatar.width * scale / 2f,
                centerY - avatar.height * scale / 2f
            )
            shader.setLocalMatrix(avatarMatrix)
            avatarPaint.shader = shader
            canvas.drawCircle(centerX, centerY, avatarRadius, avatarPaint)
        } else {
            markPaint.color = Color.rgb(88, 112, 220)
            canvas.drawCircle(centerX, centerY, avatarRadius, markPaint)
            badgeLetterPaint.textSize = 34f * density
            canvas.drawText(
                "N",
                centerX,
                centerY + badgeLetterPaint.textSize * 0.35f,
                badgeLetterPaint
            )
            badgeLetterPaint.textSize = 18f * density
        }

        val badgeX = centerX + avatarRadius * 0.72f
        val badgeY = centerY + avatarRadius * 0.72f
        val badgeRadius = 16f * density
        markPaint.shader = LinearGradient(
            badgeX - badgeRadius,
            badgeY - badgeRadius,
            badgeX + badgeRadius,
            badgeY + badgeRadius,
            intArrayOf(Color.rgb(66, 91, 205), Color.rgb(123, 156, 255)),
            null,
            Shader.TileMode.CLAMP
        )
        avatarRingPaint.strokeWidth = 2.5f * density
        canvas.drawCircle(badgeX, badgeY, badgeRadius + 2.5f * density, avatarRingPaint)
        canvas.drawCircle(
            badgeX,
            badgeY,
            badgeRadius,
            markPaint
        )
        canvas.drawText(
            "N",
            badgeX,
            badgeY + badgeLetterPaint.textSize * 0.35f,
            badgeLetterPaint
        )
        canvas.restore()

        val titleY = centerY + 145f * density
        canvas.drawText(title, centerX, titleY, titlePaint)

        val statusIndex = (phase * statuses.size).toInt()
            .coerceIn(0, statuses.lastIndex)
        val localPhase = (phase * statuses.size) % 1f
        statusPaint.alpha = (
            255f * minOf(1f, localPhase * 4f, (1f - localPhase) * 4f)
        ).toInt().coerceIn(0, 255)
        canvas.drawText(statuses[statusIndex], centerX, titleY + 31f * density, statusPaint)
        statusPaint.alpha = 255

        val trackWidth = 152f * density
        val trackLeft = centerX - trackWidth / 2f
        val trackRight = centerX + trackWidth / 2f
        val trackY = titleY + 67f * density
        canvas.drawLine(trackLeft, trackY, trackRight, trackY, progressTrackPaint)
        val pulseWidth = trackWidth * 0.28f
        val travel = (phase * 1.35f) % 1f
        val pulseCenter = trackLeft + pulseWidth / 2f +
            travel * (trackWidth - pulseWidth)
        canvas.drawLine(
            pulseCenter - pulseWidth / 2f,
            trackY,
            pulseCenter + pulseWidth / 2f,
            trackY,
            progressPaint
        )

        brandPaint.alpha = 165
        canvas.drawText(
            "NESTSPACE",
            centerX,
            height - 54f * density,
            brandPaint
        )
        brandPaint.alpha = 255
    }
}

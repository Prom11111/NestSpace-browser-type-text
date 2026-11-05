package com.coke.nestspace.control

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import com.coke.nestspace.container.StageAActivity
import com.coke.nestspace.data.Bounds
import com.coke.nestspace.data.UiNode
import com.coke.nestspace.data.UiSnapshot
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * In-process control for the virtual container.
 *
 * A target Activity's decor view is embedded into the host StageAActivity's
 * view hierarchy, so NestSpace already owns it. That means observation,
 * screenshots and touch injection can work without an AccessibilityService.
 * Coordinates are relative to the target decor view, which is also the origin
 * of screenshots taken here.
 */
object ContainerUiController {

    // Node ids are stable per View rather than per snapshot. A second
    // observe (another agent/session) must not make an id returned by the
    // first observe resolve to a different node.
    private val viewsById = mutableMapOf<String, WeakReference<View>>()
    private val idsByView = WeakHashMap<View, String>()
    private var nextNodeId = 0

    fun isAvailable(): Boolean = StageAActivity.activeTargetActivity() != null

    suspend fun snapshot(): UiSnapshot? = withContext(Dispatchers.Main.immediate) {
        val target = StageAActivity.activeTargetActivity() ?: return@withContext null
        val decor = target.window?.decorView ?: return@withContext null
        ensureLaidOut(decor)
        if (decor.width <= 0 || decor.height <= 0) return@withContext null

        val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
        viewsById.entries.removeAll { it.value.get() == null }
        val nodes = mutableListOf<UiNode>()
        visibleWindowRoots(target, decor).forEach { root ->
            // Dialog windows are real windows and may not have gone through a
            // traversal while the host task is in the background. Lay them
            // out so their nodes have usable bounds.
            ensureLaidOut(root)
            collect(root, nodes, origin, 0)
        }
        UiSnapshot(
            packageName = StageAActivity.activeTargetPackage().orEmpty(),
            activity = target.javaClass.name,
            nodes = nodes,
            screenWidth = decor.width,
            screenHeight = decor.height,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * A headless host can move to the back before its first traversal. Lay the
     * decor out at screen size so observation and screenshots still work.
     */
    private fun ensureLaidOut(view: View) {
        if (view.width > 0 && view.height > 0) return
        val metrics = view.resources.displayMetrics
        val width = if (view.width > 0) view.width else metrics.widthPixels
        val height = if (view.height > 0) view.height else metrics.heightPixels
        if (width <= 0 || height <= 0) return
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
    }

    private fun collect(view: View, out: MutableList<UiNode>, origin: IntArray, depth: Int) {
        if (depth > 100 || view.visibility != View.VISIBLE) return

        val text = (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
        val desc = view.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val editable = view is EditText
        val scrollable = view.canScrollVertically(1) ||
            view.canScrollVertically(-1) ||
            view.canScrollHorizontally(1) ||
            view.canScrollHorizontally(-1)
        val clickable = view.isClickable

        if (text != null || desc != null || editable || scrollable || clickable) {
            val location = IntArray(2).also { view.getLocationOnScreen(it) }
            val left = location[0] - origin[0]
            val top = location[1] - origin[1]
            val id = idsByView.getOrPut(view) {
                "v${nextNodeId++}".also { viewsById[it] = WeakReference(view) }
            }
            out.add(
                UiNode(
                    id = id,
                    role = view.javaClass.simpleName,
                    text = text,
                    desc = desc,
                    bounds = Bounds(left, top, left + view.width, top + view.height),
                    clickable = clickable,
                    editable = editable,
                    scrollable = scrollable,
                    enabled = view.isEnabled,
                    focused = view.isFocused,
                    selected = view.isSelected
                )
            )
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collect(view.getChildAt(i), out, origin, depth + 1)
            }
        }
    }

    suspend fun tapNode(id: String): Boolean {
        val view = withContext(Dispatchers.Main.immediate) {
            viewsById[id]?.get()?.takeIf { it.isAttachedToWindow }
        } ?: return false
        return tapView(view)
    }

    suspend fun tapText(text: String): Boolean {
        val node = snapshot()?.nodes?.firstOrNull {
            it.text?.contains(text) == true || it.desc?.contains(text) == true
        } ?: return false
        return tapNode(node.id)
    }

    suspend fun tapView(view: View): Boolean = withContext(Dispatchers.Main.immediate) {
        val target = StageAActivity.activeTargetActivity() ?: return@withContext false
        val decor = target.window?.decorView ?: return@withContext false
        ensureLaidOut(decor)
        val roots = visibleWindowRoots(target, decor)
        val root = findRoot(view, roots) ?: decor
        ensureLaidOut(root)
        val origin = IntArray(2).also { root.getLocationOnScreen(it) }
        val location = IntArray(2).also { view.getLocationOnScreen(it) }
        dispatchTap(
            root,
            (location[0] - origin[0] + view.width / 2f),
            (location[1] - origin[1] + view.height / 2f)
        )
    }

    suspend fun tap(x: Int, y: Int): Boolean = withContext(Dispatchers.Main.immediate) {
        val target = StageAActivity.activeTargetActivity() ?: return@withContext false
        val decor = target.window?.decorView ?: return@withContext false
        ensureLaidOut(decor)
        val roots = visibleWindowRoots(target, decor)
        val root = roots.lastOrNull { containsPoint(it, x, y) } ?: decor
        ensureLaidOut(root)
        val origin = IntArray(2).also { root.getLocationOnScreen(it) }
        dispatchTap(root, (x - origin[0]).toFloat(), (y - origin[1]).toFloat())
    }

    private suspend fun dispatchTap(root: View, x: Float, y: Float): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 60, MotionEvent.ACTION_UP, x, y, 0)
        val downHandled = root.dispatchTouchEvent(down)
        down.recycle()
        delay(60)
        val upHandled = root.dispatchTouchEvent(up)
        up.recycle()
        return downHandled || upHandled
    }

    /**
     * A target Activity's decor is embedded in the host, but dialogs and
     * bottom sheets created by the target are real windows. Include those
     * roots for observation, taps and screenshots so a headless agent can
     * dismiss privacy prompts without an AccessibilityService.
     */
    private fun visibleWindowRoots(target: Activity, decor: View): List<View> {
        val hostRoot = decor.rootView
        val token = decor.windowToken
        val targetPackage = StageAActivity.activeTargetPackage()
        val extras = globalWindowRoots()
            .filter { (view, params) ->
                val belongsToTarget = params?.packageName != null &&
                    params.packageName == targetPackage &&
                    params.type == WindowManager.LayoutParams.TYPE_APPLICATION
                view !== decor &&
                    view !== hostRoot &&
                    view.isShown &&
                    ((token != null && params?.token === token) || belongsToTarget)
            }
            .map { it.first }
        return listOf(decor) + extras
    }

    private fun globalWindowRoots(): List<Pair<View, WindowManager.LayoutParams?>> =
        runCatching {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val instance = cls.getMethod("getInstance").invoke(null)
            val views = cls.getDeclaredField("mViews")
                .apply { isAccessible = true }
                .get(instance) as? List<*>
            val params = cls.getDeclaredField("mParams")
                .apply { isAccessible = true }
                .get(instance) as? List<*>
            views.orEmpty().mapIndexedNotNull { index, value ->
                (value as? View)?.let {
                    it to (params?.getOrNull(index) as? WindowManager.LayoutParams)
                }
            }
        }.getOrDefault(emptyList())

    private fun findRoot(view: View, roots: List<View>): View? {
        var current: View? = view
        while (current != null) {
            if (roots.any { it === current }) return current
            current = current.parent as? View
        }
        return null
    }

    private fun containsPoint(root: View, x: Int, y: Int): Boolean {
        if (!root.isShown || root.width <= 0 || root.height <= 0) return false
        val location = IntArray(2).also { root.getLocationOnScreen(it) }
        return x in location[0] until (location[0] + root.width) &&
            y in location[1] until (location[1] + root.height)
    }

    suspend fun setText(id: String, text: String): Boolean {
        val nodeId = id.ifBlank {
            snapshot()?.nodes?.firstOrNull { it.editable }?.id.orEmpty()
        }
        if (nodeId.isBlank()) return false
        return withContext(Dispatchers.Main.immediate) {
            val editable = viewsById[nodeId]?.get() as? EditText
                ?: return@withContext false
            if (!editable.isAttachedToWindow) return@withContext false
            editable.setText(text)
            true
        }
    }

    @Suppress("DEPRECATION")
    suspend fun pressBack(): Boolean = withContext(Dispatchers.Main.immediate) {
        val host = StageAActivity.activeHost() ?: return@withContext false
        if (host.isFinishing || host.isDestroyed) return@withContext false
        host.onBackPressed()
        true
    }

    /** Bring the existing container task to the front without recreating the Activity. */
    suspend fun bringToFront(): Boolean = withContext(Dispatchers.Main.immediate) {
        val host = StageAActivity.activeHost() ?: return@withContext false
        if (host.isFinishing || host.isDestroyed) return@withContext false
        host.requestForeground()
    }

    suspend fun waitFor(text: String, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val snapshot = snapshot() ?: return false
            if (snapshot.nodes.any {
                    it.text?.contains(text) == true || it.desc?.contains(text) == true
                }
            ) {
                return true
            }
            delay(250)
        }
        return false
    }

    suspend fun screenshotBase64(): String? =
        screenshotBytes()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    /** Smaller JPEG for vision models; keeps the model request within budget. */
    suspend fun screenshotJpegBase64(
        maxWidth: Int = 640,
        quality: Int = 58
    ): String? {
        val png = screenshotBytes() ?: return null
        return withContext(Dispatchers.IO) {
            val source = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return@withContext null
            val target = if (source.width > maxWidth) {
                Bitmap.createScaledBitmap(
                    source,
                    maxWidth,
                    (source.height * (maxWidth.toFloat() / source.width)).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                source
            }
            val out = ByteArrayOutputStream()
            val ok = target.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (target !== source) target.recycle()
            source.recycle()
            if (!ok) return@withContext null
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    suspend fun screenshotBytes(): ByteArray? = withContext(Dispatchers.Main.immediate) {
        val target = StageAActivity.activeTargetActivity() ?: return@withContext null
        val decor = target.window?.decorView ?: return@withContext null
        ensureLaidOut(decor)
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) return@withContext null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
        visibleWindowRoots(target, decor).forEach { root ->
            val location = IntArray(2).also { root.getLocationOnScreen(it) }
            canvas.save()
            canvas.translate(
                (location[0] - origin[0]).toFloat(),
                (location[1] - origin[1]).toFloat()
            )
            root.draw(canvas)
            canvas.restore()
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        stream.toByteArray()
    }

    suspend fun snapshotJson(): String? = snapshot()?.let { snapshot ->
        val nodes = JSONArray()
        snapshot.nodes.forEach { node ->
            nodes.put(
                JSONObject()
                    .put("id", node.id)
                    .put("role", node.role)
                    .put("text", node.text ?: JSONObject.NULL)
                    .put("desc", node.desc ?: JSONObject.NULL)
                    .put(
                        "bounds",
                        JSONArray(
                            listOf(
                                node.bounds.left,
                                node.bounds.top,
                                node.bounds.right,
                                node.bounds.bottom
                            )
                        )
                    )
                    .put("clickable", node.clickable)
                    .put("editable", node.editable)
                    .put("scrollable", node.scrollable)
                    .put("enabled", node.enabled)
                    .put("focused", node.focused)
                    .put("selected", node.selected)
            )
        }
        JSONObject()
            .put("package", snapshot.packageName)
            .put("activity", snapshot.activity)
            .put(
                "screen",
                JSONObject().put("width", snapshot.screenWidth).put("height", snapshot.screenHeight)
            )
            .put("nodes", nodes)
            .put("timestamp_ms", snapshot.timestamp)
            .toString()
    }
}

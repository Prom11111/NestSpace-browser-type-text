package com.coke.nestspace.container

import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.container.identity.ProcessNameCompat
import com.coke.nestspace.container.loader.withContainerClassLoader


import android.app.Activity
import android.app.ActivityManager
import android.app.Presentation
import android.app.PictureInPictureParams
import android.content.Intent
import android.hardware.display.DisplayManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.Rational
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.coke.nestspace.MainActivity
import com.coke.nestspace.R
import com.coke.nestspace.assistant.FloatingDotOverlay
import com.coke.nestspace.container.activity.TargetApplicationLifecycle
import com.coke.nestspace.container.activity.TargetLifecycle
import com.coke.nestspace.container.activity.TargetStateStore
import com.coke.nestspace.ui.DesktopNavigation

/**
 * Host Activity for one target Activity. Each in-app navigation creates another
 * StageAActivity on top; Android's own back stack then provides the target
 * app's back behavior. All hosts for one app launch share a ContainerSession
 * (Application, ClassLoader, Resources) via ContainerSessions.
 *
 * Keeping a real host token per target Activity is deliberate: when the target
 * calls finish(), only its host closes, and the previous host resumes. This is
 * what makes splash -> home and startActivityForResult behave normally.
 */
open class StageAActivity : Activity() {

    companion object {
        const val ACTION_FOREGROUND = "com.coke.nestspace.container.FOREGROUND"
        const val EXTRA_TOKEN = "container_token"
        const val EXTRA_SESSION_ID = "container_session_id"
        const val EXTRA_LAUNCH_PACKAGE = "container_launch_package"
        const val EXTRA_LAUNCH_INSTANCE = "container_launch_instance"
        const val EXTRA_ACTIVITY_CLASS = "container_activity_class"
        const val EXTRA_TARGET_INTENT = "container_target_intent"
        const val EXTRA_HEADLESS = "container_headless"
        const val EXTRA_MIRROR_DISPLAY_ID = "container_mirror_display_id"
        const val EXTRA_TARGET_PROCESS = "container_target_process"
        private const val TAG = "StageAActivity"
        private const val KEY_HOST_STATE_ID = "nestspace.host_state_id"

        @Volatile
        private var active: StageAActivity? = null

        /**
         * The target Activity currently hosted, for in-process control tools.
         * Null when no container Activity has been shown in this process yet.
         */
        fun activeTargetActivity(): Activity? = active?.current

        fun activeTargetPackage(): String? = active?.currentPackageName

        fun activeHost(): StageAActivity? = active

        fun finishIfShowing(packageName: String) {
            val activity = active ?: return
            activity.runOnUiThread {
                if (activity.currentPackageName == packageName) activity.returnToDesktop()
            }
        }
    }

    private var root: FrameLayout? = null
    private var session: ContainerSession? = null
    private var sessionRetained = false
    private var current: Activity? = null
    private var currentPackageName: String? = null
    private var pendingTargetState: Bundle? = null
    private var targetStateKey: String? = null
    private var hostStateId = 0
    private var targetStarted = false
    private var targetPostCreated = false
    private var targetRestored = false
    private var targetAttached = false
    private var targetResumed = false
    private var targetStopped = false
    private var hostResumed = false
    private var headless = false
    private var headlessBackDone = false
    private var pipAttempted = false
    private var pipActive = false
    private var mirrorDisplayId = -1
    private var mirrorMode = false
    private var mirrorBackDone = false
    private var mirrorPresentation: Presentation? = null
    private var mirrorRoot: FrameLayout? = null
    private var targetStartScheduled = false
    private var predictiveBackCallback: Any? = null

    /** The target Activity hosted by this host, for permission routing. */
    fun currentTargetActivity(): Activity? = current

    /**
     * Leave headless mode and move this host's existing task to the front.
     * Called inside the container process so no new Activity/Instance is
     * created and the target keeps its current page and session.
     */
    fun requestForeground(): Boolean {
        headless = false
        headlessBackDone = false
        session?.headless = false
        root?.alpha = 1f
        return runCatching {
            val manager = getSystemService(ActivityManager::class.java) ?: return false
            manager.moveTaskToFront(taskId, 0)
            true
        }.getOrElse { false }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent.getIntExtra(EXTRA_MIRROR_DISPLAY_ID, -1) >= 0) {
            setTheme(R.style.Theme_Virtual_Container_Mirror)
        }
        super.onCreate(savedInstanceState)
        hostStateId = TargetStateStore.nextHostId()
        active = this
        ContainerProcessPool.markCurrentSlot(slotIndexFor(javaClass))
        ContainerProcessPool.markSlotProcess(this, slotIndexFor(javaClass))
        headless = intent.getBooleanExtra(EXTRA_HEADLESS, false)
        mirrorDisplayId = intent.getIntExtra(EXTRA_MIRROR_DISPLAY_ID, -1)
        val frame = HeadlessScaleFrame(this)
        root = frame
        if (mirrorDisplayId >= 0) {
            mirrorMode = setupMirrorPresentation(mirrorDisplayId)
        }
        if (mirrorMode) {
            // The real target view lives on the full-resolution mirror
            // display. This host window only keeps the Activity token alive.
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )
            frame.setBackgroundColor(Color.TRANSPARENT)
        } else if (headless) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            frame.realWidth = metrics.widthPixels
            frame.realHeight = metrics.heightPixels
            frame.scaleToRealResolution = true
            // A headless launch still has to become RESUMED before the target
            // can run. Keep the launch window invisible until the task is sent
            // to the back, otherwise the app flashes on screen for a frame or
            // two while the placeholder/target first draws.
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )
            frame.setBackgroundColor(Color.rgb(247, 248, 250))
            frame.alpha = 0f
            // PiP briefly shows an empty system window while the target
            // starts. Draw a loading surface so it is not blank for seconds.
            val loading = TextView(this).apply {
                text = AppLanguage.tr("正在无头启动…", "Starting headless…")
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(24, 25, 31))
                textSize = 16f
            }
            frame.addView(
                loading,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } else {
            showLaunchPlaceholder(frame)
        }
        setContentView(frame)
        if (mirrorMode) {
            val parameters = window.attributes
            parameters.width = 1
            parameters.height = 1
            window.attributes = parameters
            window.setGravity(Gravity.TOP or Gravity.START)
        }
        registerPredictiveBack()
        if (mirrorMode) {
            // Move the token-only host task behind the assistant immediately,
            // before the target starts and before this window can draw black.
            frame.post {
                if (mirrorMode && !isFinishing && !isDestroyed) {
                    @Suppress("DEPRECATION")
                    moveTaskToBack(true)
                }
            }
        }
        scheduleTargetStart(savedInstanceState)
    }

    /**
     * Target Application.onCreate can take many seconds (Bilibili spends 10s+
     * in Gripper before it returns). Running it from onCreate keeps the host
     * window from drawing and Android's activity launch timeout then force
     * finishes the container. Draw a placeholder first, then start the target
     * from the next main-loop turn so the host is considered launched.
     */
    private fun scheduleTargetStart(savedState: Bundle?) {
        if (targetStartScheduled) return
        targetStartScheduled = true
        val frame = root ?: return
        val launchIntent = Intent(intent)
        frame.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    frame.viewTreeObserver.removeOnPreDrawListener(this)
                    frame.post {
                        if (isFinishing || isDestroyed) return@post
                        handleIntent(launchIntent, savedState)
                        session?.headless = headless
                    }
                    return true
                }
            }
        )
        frame.requestLayout()
    }

    private fun slotIndexFor(activityClass: Class<*>): Int = when (activityClass) {
        StageASlot1Activity::class.java -> 0
        StageASlot2Activity::class.java -> 1
        StageASlot3Activity::class.java -> 2
        StageASlot4Activity::class.java -> 3
        StageASlot5Activity::class.java -> 4
        StageASlot6Activity::class.java -> 5
        StageASlot7Activity::class.java -> 6
        StageASlot8Activity::class.java -> 7
        StageASlot9Activity::class.java -> 8
        StageASlot10Activity::class.java -> 9
        StageASlot11Activity::class.java -> 10
        StageASlot12Activity::class.java -> 11
        StageASlot13Activity::class.java -> 12
        StageASlot14Activity::class.java -> 13
        StageASlot15Activity::class.java -> 14
        StageASlot16Activity::class.java -> 15
        else -> -1
    }

    private fun setupMirrorPresentation(id: Int): Boolean {
        val manager = getSystemService(DisplayManager::class.java) ?: return false
        val display = manager.getDisplay(id) ?: return false
        return runCatching {
            val presentation = Presentation(this, display)
            val content = FrameLayout(presentation.context)
            presentation.setContentView(content)
            presentation.show()
            mirrorPresentation = presentation
            mirrorRoot = content
            true
        }.getOrElse {
            Log.w(TAG, "mirror presentation failed", it)
            mirrorPresentation = null
            mirrorRoot = null
            false
        }
    }

    private fun showLaunchPlaceholder(frame: FrameLayout) {
        // Do not let the loading window receive focus. Target startup can hold
        // the main thread for several seconds (Bilibili opens 35 dex files and
        // runs Gripper before its Application.onCreate returns); if the window
        // is focusable Android reports an input-dispatch ANR before the target
        // ever gets a chance to draw. Focus is restored as soon as the target
        // view is attached in openActivity().
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        frame.setBackgroundColor(Color.WHITE)
        val label = TextView(this).apply {
            text = AppLanguage.tr("正在启动…", "Starting…")
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(24, 25, 31))
            textSize = 16f
        }
        frame.addView(
            label,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun restoreLoadingWindowFocus() {
        window.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        window.decorView.requestFocus()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        session?.loader?.let { intent.setExtrasClassLoader(it) }

        if (intent.action == ACTION_FOREGROUND) {
            // Keep the original launch intent as the Activity intent; this
            // foreground hint has no session/activity extras and would break
            // recreation after an unhandled configuration change.
            headless = false
            headlessBackDone = false
            session?.headless = false
            root?.alpha = 1f
            return
        }
        setIntent(intent)

        val target = current
        val incomingSession = intent.getStringExtra(EXTRA_SESSION_ID)
            ?: intent.getStringExtra(EXTRA_LAUNCH_PACKAGE)?.let { pkg ->
                intent.getStringExtra(EXTRA_LAUNCH_INSTANCE)?.let { "$pkg@$it" }
            }
        val sameSession = incomingSession == null || incomingSession == session?.id
        val incomingClass = intent.getStringExtra(EXTRA_ACTIVITY_CLASS)
        if (target != null && sameSession && !target.isFinishing &&
            incomingClass != null && target.javaClass.name == incomingClass
        ) {
            headless = intent.getBooleanExtra(EXTRA_HEADLESS, headless)
            session?.headless = headless
            val targetIntent = targetIntentFrom(intent) ?: Intent(intent)
            withTargetLoader {
                runCatching {
                    target.setIntent(targetIntent)
                    session?.instrumentation?.callActivityOnNewIntent(target, targetIntent)
                }.onFailure { Log.w(TAG, "target onNewIntent failed", it) }
            }
            if (headless) {
                headlessBackDone = false
                root?.alpha = 0f
                scheduleHeadlessPipOrBack()
            } else {
                root?.alpha = 1f
            }
            return
        }

        headless = intent.getBooleanExtra(EXTRA_HEADLESS, false)
        closeCurrent()
        releaseSession()
        handleIntent(intent, null)
        session?.headless = headless
        if (headless) {
            headlessBackDone = false
            root?.alpha = 0f
            scheduleHeadlessPipOrBack()
        } else {
            root?.alpha = 1f
        }
    }

    override fun onRestart() {
        super.onRestart()
        val target = current ?: return
        if (targetStopped) {
            withTargetLoader {
                if (!TargetLifecycle.perform(target, "performRestart", arrayOf<Any?>(false))) {
                    runCatching { session?.instrumentation?.callActivityOnRestart(target) }
                        .onFailure { Log.w(TAG, "target onRestart failed", it) }
                }
            }
            targetStopped = false
        }
    }

    override fun onStart() {
        super.onStart()
        val target = current ?: return
        if (!targetStarted) {
            withTargetLoader {
                if (!TargetLifecycle.perform(
                        target,
                        "performStart",
                        arrayOf<Any?>("StageAActivity.onStart"),
                        emptyArray<Any?>()
                    )
                ) {
                    runCatching { session?.instrumentation?.callActivityOnStart(target) }
                        .onFailure { Log.w(TAG, "target onStart failed", it) }
                }
            }
            targetStarted = true
            dispatchTargetApplicationLifecycle("dispatchActivityStarted", target)
        }
        callTargetRestoreState(target)
        callTargetPostCreate(target)
    }

    override fun onResume() {
        super.onResume()
        ContainerProcessPool.markActiveSlot(slotIndexFor(javaClass))
        Log.d(TAG, "host onResume target=${current?.javaClass?.name} headless=$headless")
        active = this
        hostResumed = true
        session?.navigator = this
        FloatingDotOverlay.publishContainerMode(this, true)
        scheduleHeadlessPipOrBack()
        if (mirrorMode && !mirrorBackDone) {
            mirrorBackDone = true
            // The mirror has its own window on the virtual display. Keep the
            // host task out of the way so the assistant remains visible.
            root?.postDelayed({
                if (mirrorMode && !isFinishing && !isDestroyed) {
                    @Suppress("DEPRECATION")
                    moveTaskToBack(true)
                }
            }, 300)
        }
        val target = current ?: return
        session?.context?.hostWindowManager = windowManager
        if (!targetResumed) {
            withTargetLoader {
                val performed = TargetLifecycle.perform(
                    target,
                    "performResume",
                    arrayOf<Any?>(false, "StageAActivity.onResume"),
                    emptyArray<Any?>()
                )
                if (!performed) {
                    runCatching { session?.instrumentation?.callActivityOnResume(target) }
                        .onFailure { Log.w(TAG, "target onResume failed", it) }
                    runCatching { TargetLifecycle.postResume(target) }
                        .onFailure { Log.w(TAG, "target onPostResume failed", it) }
                }
            }
            targetResumed = true
            dispatchTargetApplicationLifecycle("dispatchActivityResumed", target)
            attachTarget(target)
        }
    }

    /**
     * Prefer a system picture-in-picture window for headless work. The app
     * stays visible and alive in a small window, so there is no full-screen
     * black transition and ui_observe/screenshot can keep working.
     * Move-to-back is only a fallback when PiP is unavailable.
     */
    private fun scheduleHeadlessPipOrBack() {
        if (!headless || pipActive || mirrorMode || pipAttempted) return
        pipAttempted = true
        val entered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(9, 16))
                        .build()
                )
            }.getOrDefault(false)
        } else {
            false
        }
        if (entered) {
            pipActive = true
            root?.alpha = 1f
            Log.d(TAG, "headless launch entered PiP")
        } else {
            Log.d(TAG, "headless PiP unavailable, moving task to back")
            scheduleHeadlessBack()
        }
    }

    @Suppress("DEPRECATION")
    private fun scheduleHeadlessBack() {
        if (pipActive) return
        if (!headless || headlessBackDone) return
        headlessBackDone = true
        Log.d(TAG, "headless launch, moving task to back")
        root?.postDelayed({
            if (!headless || isFinishing || isDestroyed) return@postDelayed
            overridePendingTransition(0, 0)
            val moved = moveTaskToBack(true)
            // Keep alpha at 0 until onStop. Restoring it here can still paint
            // one white frame while the task transition is in flight.
            if (!moved) root?.alpha = 1f
            Log.d(TAG, "moveTaskToBack(headless) = $moved")
        }, 300)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val target = current ?: return
        withTargetLoader {
            runCatching { target.onWindowFocusChanged(hasFocus) }
                .onFailure { Log.w(TAG, "target onWindowFocusChanged failed", it) }
        }
    }

    override fun onPause() {
        // In PiP the activity is paused but the target is still the app the
        // assistant is controlling. Keep its slot marked active so the
        // process pool does not evict and kill it while the user watches it.
        if (!pipActive && !mirrorMode) {
            ContainerProcessPool.clearActiveSlot(slotIndexFor(javaClass))
        }
        Log.d(TAG, "host onPause target=${current?.javaClass?.name}")
        FloatingDotOverlay.publishContainerMode(this, false)
        hostResumed = false
        if (pipActive || mirrorMode) {
            // Keep the target resumed while it is visible in PiP. The host
            // window is paused by the system, but pausing the synthetic
            // target as well stops WebView/Compose drawing and leaves the
            // PiP window blank indefinitely.
            super.onPause()
            return
        }
        if (targetResumed) {
            current?.let {
                withTargetLoader {
                    runCatching { session?.instrumentation?.callActivityOnPause(it) }
                        .onFailure { Log.w(TAG, "target onPause failed", it) }
                }
            }
            current?.let { dispatchTargetApplicationLifecycle("dispatchActivityPaused", it) }
            targetResumed = false
        }
        super.onPause()
    }

    override fun onStop() {
        Log.d(TAG, "host onStop target=${current?.javaClass?.name}")
        // The headless window is no longer visible; restore drawing so
        // ui_observe/ui_screenshot still see the target hierarchy.
        if (headless) root?.alpha = 1f
        if (pipActive || mirrorMode) {
            // PiP can deliver onStop when its task is backgrounded, but the
            // small window may still be alive. Keep the target paused rather
            // than destroying the page the assistant is reading.
            super.onStop()
            return
        }
        if (targetStarted) {
            current?.let {
                withTargetLoader {
                    if (!TargetLifecycle.perform(
                            it,
                            "performStop",
                            arrayOf<Any?>(false, "StageAActivity.onStop"),
                            arrayOf<Any?>(false),
                            emptyArray<Any?>()
                        )
                    ) {
                        runCatching { session?.instrumentation?.callActivityOnStop(it) }
                            .onFailure { Log.w(TAG, "target onStop failed", it) }
                    }
                }
            }
            current?.let { dispatchTargetApplicationLifecycle("dispatchActivityStopped", it) }
            targetStarted = false
            targetStopped = true
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val target = current
        if (target != null) {
            val targetState = Bundle()
            withTargetLoader {
                runCatching {
                    session?.instrumentation?.callActivityOnSaveInstanceState(target, targetState)
                }.onFailure { Log.w(TAG, "target onSaveInstanceState failed", it) }
            }
            dispatchTargetApplicationLifecycle(
                "dispatchActivitySaveInstanceState",
                target,
                targetState
            )
            targetStateKey?.let { TargetStateStore.put(it, targetState) }
        }
        outState.putInt(KEY_HOST_STATE_ID, hostStateId)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        // The host window hierarchy is the target's view tree. Restoring it
        // unmarshals target-only Parcelables (for example AndroidX
        // DrawerLayout.SavedState) with the host class loader, which throws
        // BadParcelableException and kills the slot process. Target lifecycle
        // state is restored separately from TargetStateStore below, so a
        // failed host hierarchy restore only loses transient view state.
        runCatching { super.onRestoreInstanceState(savedInstanceState) }
            .onFailure { Log.w(TAG, "host state restore failed", it) }
        current?.let { callTargetRestoreState(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val target = current
        withTargetLoader {
            target?.let {
                runCatching {
                    it.resources.updateConfiguration(newConfig, it.resources.displayMetrics)
                    it.onConfigurationChanged(newConfig)
                }.onFailure { Log.w(TAG, "target onConfigurationChanged failed", it) }
            }
            runCatching { session?.application?.onConfigurationChanged(newConfig) }
                .onFailure { Log.w(TAG, "application onConfigurationChanged failed", it) }
        }
    }

    override fun onMultiWindowModeChanged(
        isInMultiWindowMode: Boolean,
        newConfig: Configuration
    ) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        val target = current ?: return
        withTargetLoader {
            runCatching { target.onMultiWindowModeChanged(isInMultiWindowMode, newConfig) }
                .onFailure { Log.w(TAG, "target onMultiWindowModeChanged failed", it) }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            pipActive = true
            root?.alpha = 1f
            // Re-measure the full-resolution target at the new PiP window
            // size; HeadlessScaleFrame applies the fitting scale.
            root?.requestLayout()
        }
        val target = current ?: return
        withTargetLoader {
            runCatching {
                target.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
            }.onFailure { Log.w(TAG, "target onPictureInPictureModeChanged failed", it) }
        }
    }

    override fun onTopResumedActivityChanged(isTopResumed: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        super.onTopResumedActivityChanged(isTopResumed)
        val target = current ?: return
        withTargetLoader {
            runCatching { target.onTopResumedActivityChanged(isTopResumed) }
                .onFailure { Log.w(TAG, "target onTopResumedActivityChanged failed", it) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        current?.let { attachTarget(it) }
    }

    override fun onDetachedFromWindow() {
        current?.let { detachTarget(it) }
        super.onDetachedFromWindow()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val target = current ?: return
        withTargetLoader {
            runCatching { session?.instrumentation?.callActivityOnUserLeaving(target) }
                .onFailure { Log.w(TAG, "target onUserLeaveHint failed", it) }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        val target = current ?: return
        withTargetLoader {
            runCatching { target.onUserInteraction() }
                .onFailure { Log.w(TAG, "target onUserInteraction failed", it) }
        }
    }

    override fun onActivityReenter(resultCode: Int, data: Intent?) {
        super.onActivityReenter(resultCode, data)
        val target = current ?: return
        withTargetLoader {
            runCatching { target.onActivityReenter(resultCode, data) }
                .onFailure { Log.w(TAG, "target onActivityReenter failed", it) }
        }
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        val target = current ?: return
        withTargetLoader {
            runCatching { target.onEnterAnimationComplete() }
                .onFailure { Log.w(TAG, "target onEnterAnimationComplete failed", it) }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        val target = current
        withTargetLoader {
            target?.let {
                runCatching { it.onLowMemory() }
                    .onFailure { Log.w(TAG, "target onLowMemory failed", it) }
            }
            runCatching { session?.application?.onLowMemory() }
                .onFailure { Log.w(TAG, "application onLowMemory failed", it) }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val target = current
        withTargetLoader {
            target?.let {
                runCatching { it.onTrimMemory(level) }
                    .onFailure { Log.w(TAG, "target onTrimMemory failed", it) }
            }
            runCatching { session?.application?.onTrimMemory(level) }
                .onFailure { Log.w(TAG, "application onTrimMemory failed", it) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val target = current ?: return
        // Result Parcelables were created by the target's class loader (for
        // example Bilibili's RouteVertexSource). Without this, Bundle reads
        // them through the host loader and throws ClassNotFoundException while
        // the target handles the result (normally while navigating back).
        session?.loader?.let { data?.setExtrasClassLoader(it) }
        withTargetLoader {
            runCatching {
                val method = Activity::class.java.getDeclaredMethod(
                    "onActivityResult",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Intent::class.java
                )
                method.isAccessible = true
                method.invoke(target, requestCode, resultCode, data)
            }.onFailure { Log.w(TAG, "forward onActivityResult failed", it) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Results of a virtualized target request are delivered to the target
        // Activity by ContainerPermissions; this host callback is only the
        // transport for the real system permission dialog.
        if (session?.context?.permissions?.onHostPermissionResult(
                requestCode,
                permissions,
                grantResults
            ) == true
        ) {
            return
        }
        val target = current ?: return
        Log.d(
            TAG,
            "forward permissions result code=$requestCode " +
                permissions.zip(grantResults.toTypedArray()).joinToString()
        )
        withTargetLoader {
            runCatching {
                target.onRequestPermissionsResult(requestCode, permissions, grantResults)
            }.onFailure { Log.w(TAG, "forward permissions result failed", it) }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    /**
     * Slot activities opt out of OnBackInvoked in the manifest, so Android
     * delivers the back key to this host's onBackPressed even when the target
     * registers its own overlay callback (Pinduoduo does this for its
     * double-press exit, and previously swallowed every later press). The
     * target's own OnBackPressedDispatcher callbacks still run through
     * [handleBack]; if a base Activity/ComponentActivity implementation
     * handles back but fails to finish the host, finish it here.
     */
    private fun handleBack() {
        val target = current
        if (target == null) {
            super.onBackPressed()
            return
        }
        if (target.isFinishing) return
        Log.d(TAG, "back target=${target.javaClass.name} finishing=${target.isFinishing}")
        var failed = false
        withTargetLoader {
            runCatching { target.onBackPressed() }
                .onFailure {
                    failed = true
                    Log.w(TAG, "target onBackPressed failed", it)
                }
        }
        if (failed) {
            finish()
            return
        }
        root?.postDelayed({
            if (isFinishing || isDestroyed || current !== target || target.isFinishing) {
                return@postDelayed
            }
            val callbacks = hasEnabledBackCallbacks(target)
            val declaring = runCatching {
                target.javaClass.getMethod("onBackPressed").declaringClass
            }.getOrNull()
            Log.d(
                TAG,
                "back check callbacks=$callbacks declaring=${declaring?.name} " +
                    "targetFinishing=${target.isFinishing}"
            )
            if (callbacks) return@postDelayed
            val baseHandlesBack = declaring == Activity::class.java ||
                declaring?.name?.startsWith("androidx.activity.ComponentActivity") == true ||
                declaring?.name?.startsWith("androidx.appcompat.app.AppCompatActivity") == true
            if (baseHandlesBack) {
                Log.d(TAG, "back unhandled by ${target.javaClass.name}; finishing host")
                finish()
            }
        }, 250)
    }

    private fun hasEnabledBackCallbacks(target: Activity): Boolean = runCatching {
        val dispatcher = target.javaClass
            .getMethod("getOnBackPressedDispatcher")
            .invoke(target) ?: return@runCatching false
        dispatcher.javaClass
            .getMethod("hasEnabledCallbacks")
            .invoke(dispatcher) as? Boolean ?: false
    }.getOrDefault(false)

    private fun registerPredictiveBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            val callback = android.window.OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                callback
            )
            predictiveBackCallback = callback
        }.onFailure { Log.w(TAG, "predictive back register failed", it) }
    }

    private fun unregisterPredictiveBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = predictiveBackCallback as? android.window.OnBackInvokedCallback ?: return
        predictiveBackCallback = null
        runCatching { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    override fun onDestroy() {
        Log.d(TAG, "host onDestroy target=${current?.javaClass?.name}")
        mirrorPresentation?.let { runCatching { it.dismiss() } }
        mirrorPresentation = null
        mirrorRoot = null
        unregisterPredictiveBack()
        if (isFinishing) targetStateKey?.let { TargetStateStore.remove(it) }
        closeCurrent()
        if (session?.navigator === this) session?.navigator = null
        releaseSession()
        if (active === this) active = null
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent, savedState: Bundle?) {
        val launchPackage = intent.getStringExtra(EXTRA_LAUNCH_PACKAGE)
        val launchInstance = intent.getStringExtra(EXTRA_LAUNCH_INSTANCE)
        if (!launchPackage.isNullOrBlank() && !launchInstance.isNullOrBlank()) {
            val targetProcess = ContainerProcessRouter.normalize(
                launchPackage,
                intent.getStringExtra(EXTRA_TARGET_PROCESS)
            )
            val requestedSession = intent.getStringExtra(EXTRA_SESSION_ID)
                ?.takeIf { it.isNotBlank() }
                ?: ContainerProcessRouter.sessionId(launchPackage, launchInstance, targetProcess)
            val existing = ContainerSessions.retain(requestedSession)
            if (existing != null) {
                session = existing
                sessionRetained = true
                existing.navigator = this
                openActivity(
                    existing,
                    intent.getStringExtra(EXTRA_ACTIVITY_CLASS) ?: existing.spec.launcherActivity,
                    targetIntentFrom(intent),
                    savedState
                )
                return
            }
            try {
                val spec = ContainerSpecFactory.build(
                    this,
                    launchPackage,
                    launchInstance,
                    requestedSession,
                    targetProcess
                )
                val created = ContainerSessions.acquire(this, spec)
                session = created
                sessionRetained = true
                created.navigator = this
                openActivity(
                    created,
                    intent.getStringExtra(EXTRA_ACTIVITY_CLASS) ?: spec.launcherActivity,
                    targetIntentFrom(intent),
                    savedState
                )
            } catch (t: Throwable) {
                showError(
                    AppLanguage.tr(
                        "容器启动失败：${t.message ?: t.javaClass.name}\n\n" +
                            t.stackTrace.take(8).joinToString("\n"),
                        "Container failed to start: ${t.message ?: t.javaClass.name}\n\n" +
                            t.stackTrace.take(8).joinToString("\n")
                    )
                )
            }
            return
        }

        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        if (!sessionId.isNullOrBlank()) {
            val existing = ContainerSessions.retain(sessionId)
            if (existing == null) {
                showError(
                    AppLanguage.tr(
                        "容器会话已失效，请回到 NestSpace 重新启动",
                        "The container session expired. Open it again from NestSpace."
                    )
                )
                return
            }
            session = existing
            sessionRetained = true
            existing.navigator = this
            val className = intent.getStringExtra(EXTRA_ACTIVITY_CLASS)
                ?: existing.spec.launcherActivity
            openActivity(existing, className, targetIntentFrom(intent), savedState)
            return
        }

        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (token.isNullOrBlank()) {
            showError(AppLanguage.tr("容器会话参数缺失", "Container session parameters are missing"))
            return
        }
        val spec = ContainerLaunchRegistry.get(token)
        if (spec == null) {
            showError(
                    AppLanguage.tr(
                        "容器会话已失效，请回到 NestSpace 重新启动",
                        "The container session expired. Open it again from NestSpace."
                    )
                )
            return
        }

        try {
            ContainerLaunchRegistry.remove(token)
            val created = ContainerSessions.acquire(this, spec)
            session = created
            sessionRetained = true
            created.navigator = this
            val launcherIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(spec.packageName, spec.launcherActivity)
            openActivity(created, spec.launcherActivity, launcherIntent, savedState)
        } catch (t: Throwable) {
            showError(
                AppLanguage.tr(
                    "容器启动失败：${t.message ?: t.javaClass.name}\n\n" +
                        t.stackTrace.take(8).joinToString("\n"),
                    "Container failed to start: ${t.message ?: t.javaClass.name}\n\n" +
                        t.stackTrace.take(8).joinToString("\n")
                )
            )
        }
    }

    private fun openActivity(
        session: ContainerSession,
        className: String?,
        targetIntent: Intent?,
        savedState: Bundle? = null
    ) {
        try {
            session.context.hostWindowManager = windowManager
            if (mirrorMode) session.mirrorDisplayId = mirrorDisplayId
            // The host Bundle is not target state; Android passes it back
            // after host recreation. Use the id saved in it to find exactly
            // this host instance's target state, and never consume another
            // instance's state on a fresh launch.
            val restoreHostId = savedState?.getInt(KEY_HOST_STATE_ID, 0) ?: 0
            val effectiveState = if (restoreHostId > 0) {
                TargetStateStore.consume(
                    TargetStateStore.key(session, className, restoreHostId)
                )
            } else {
                null
            }
            effectiveState?.classLoader = session.loader
            targetIntent?.setExtrasClassLoader(session.loader)
            pendingTargetState = effectiveState
            targetStateKey = TargetStateStore.key(session, className, hostStateId)
            targetRestored = false
            targetAttached = false

            val target = session.createActivity(this, className, targetIntent, effectiveState)
            current = target
            currentPackageName = session.spec.packageName
            dispatchTargetApplicationLifecycle("dispatchActivityCreated", target)
            targetStarted = false
            targetPostCreated = false
            targetResumed = false
            targetStopped = false
            val frame = mirrorRoot ?: root ?: return
            frame.removeAllViews()
            frame.addView(
                target.window.decorView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            restoreLoadingWindowFocus()
            title = "${session.spec.label} · ${session.spec.instanceId}"

            if (hostResumed) {
                withTargetLoader {
                    if (!TargetLifecycle.perform(
                            target,
                            "performStart",
                            arrayOf<Any?>("StageAActivity.openStart"),
                            emptyArray<Any?>()
                        )
                    ) {
                        runCatching { session.instrumentation.callActivityOnStart(target) }
                            .onFailure { Log.w(TAG, "target onStart (open) failed", it) }
                    }
                }
                targetStarted = true
                dispatchTargetApplicationLifecycle("dispatchActivityStarted", target)
                callTargetRestoreState(target)
                callTargetPostCreate(target)
                withTargetLoader {
                    val performed = TargetLifecycle.perform(
                        target,
                        "performResume",
                        arrayOf<Any?>(false, "StageAActivity.openResume"),
                        emptyArray<Any?>()
                    )
                    if (!performed) {
                        runCatching { session.instrumentation.callActivityOnResume(target) }
                            .onFailure { Log.w(TAG, "target onResume (open) failed", it) }
                        runCatching { TargetLifecycle.postResume(target) }
                            .onFailure { Log.w(TAG, "target onPostResume (open) failed", it) }
                    }
                }
                targetResumed = true
                dispatchTargetApplicationLifecycle("dispatchActivityResumed", target)
                attachTarget(target)
            }
        } catch (t: Throwable) {
            showError(
                AppLanguage.tr(
                    "容器启动失败：${t.message ?: t.javaClass.name}\n\n" +
                        t.stackTrace.take(8).joinToString("\n"),
                    "Container failed to start: ${t.message ?: t.javaClass.name}\n\n" +
                        t.stackTrace.take(8).joinToString("\n")
                )
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun targetIntentFrom(intent: Intent): Intent? =
        intent.getParcelableExtra(EXTRA_TARGET_INTENT)

    private fun returnToDesktop() {
        DesktopNavigation.requestDesktop()
        runCatching {
            startActivity(
                Intent()
                    .setClassName(
                        MainActivity::class.java.packageName,
                        MainActivity::class.java.name
                    )
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
        }
        finish()
    }

    private fun closeCurrent() {
        val target = current
        current = null
        currentPackageName = null
        if (target != null) {
            val instrumentation = session?.instrumentation
            withTargetLoader {
                if (targetResumed) {
                    runCatching { instrumentation?.callActivityOnPause(target) }
                }
                if (targetStarted) {
                    if (!TargetLifecycle.perform(
                            target,
                            "performStop",
                            arrayOf<Any?>(false, "StageAActivity.close"),
                            arrayOf<Any?>(false),
                            emptyArray<Any?>()
                        )
                    ) {
                        runCatching { instrumentation?.callActivityOnStop(target) }
                    }
                }
                runCatching { instrumentation?.callActivityOnDestroy(target) }
            }
            dispatchTargetApplicationLifecycle("dispatchActivityDestroyed", target)
            detachTarget(target)
            session?.detach(target)
        }
        targetStarted = false
        targetPostCreated = false
        targetRestored = false
        targetAttached = false
        targetResumed = false
        targetStopped = false
        root?.removeAllViews()
    }

    private fun releaseSession() {
        val currentSession = session ?: return
        if (sessionRetained) {
            ContainerSessions.release(currentSession.id)
            sessionRetained = false
        }
        session = null
    }

    private fun withTargetLoader(block: () -> Unit) {
        val currentSession = session
        val loader = currentSession?.loader
        if (loader != null) {
            ProcessNameCompat.withProcessName(currentSession.spec.targetProcessName) {
                withContainerClassLoader(loader, block)
            }
        } else {
            block()
        }
    }

    private fun callTargetRestoreState(target: Activity) {
        if (targetRestored) return
        val state = pendingTargetState ?: return
        session?.loader?.let { state.classLoader = it }
        targetRestored = true
        withTargetLoader {
            TargetLifecycle.restore(target, session?.instrumentation, state)
        }
    }

    private fun callTargetPostCreate(target: Activity) {
        if (targetPostCreated) return
        targetPostCreated = true
        val state = pendingTargetState
        withTargetLoader {
            TargetLifecycle.postCreate(target, session?.instrumentation, state)
        }
        pendingTargetState = null
    }

    private fun dispatchTargetApplicationLifecycle(
        event: String,
        target: Activity,
        bundle: Bundle? = null
    ) {
        val application = session?.application ?: return
        withTargetLoader {
            runCatching {
                TargetApplicationLifecycle.dispatch(application, event, target, bundle)
            }.onFailure { Log.w(TAG, "$event failed", it) }
        }
    }

    private fun attachTarget(target: Activity) {
        if (targetAttached) return
        targetAttached = true
        withTargetLoader { TargetLifecycle.attach(target) }
    }

    private fun detachTarget(target: Activity) {
        if (!targetAttached) return
        targetAttached = false
        withTargetLoader { TargetLifecycle.detach(target) }
    }

    private fun showError(message: String) {
        closeCurrent()
        restoreLoadingWindowFocus()
        val frame = root ?: return
        frame.removeAllViews()
        title = AppLanguage.tr("容器启动失败", "Container failed to start")
        val text = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(Color.RED)
            setPadding(48, 96, 48, 48)
            gravity = Gravity.TOP or Gravity.START
        }
        frame.addView(
            text,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

}

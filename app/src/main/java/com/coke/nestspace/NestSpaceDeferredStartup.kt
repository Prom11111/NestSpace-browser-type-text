package com.coke.nestspace

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import com.coke.nestspace.agent.RunRecovery
import com.coke.nestspace.assistant.FloatingDotOverlay
import com.coke.nestspace.assistant.FloatingDotSettings
import com.coke.nestspace.assistant.LocalSpeechEngine
import com.coke.nestspace.assistant.LocalSpeechModelStore
import com.coke.nestspace.browser.BrowserProxyManager
import com.coke.nestspace.mcp.McpClientManager
import com.coke.nestspace.plugin.PluginCenterStore
import com.coke.nestspace.schedule.ScheduledTaskManager
import com.coke.nestspace.service.McpForegroundService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs main-process work that is not required to draw the first UI frame.
 *
 * MainActivity signals the first frame. The short fallback keeps headless
 * service and broadcast starts working when no Activity is created. All disk
 * and persisted-state work runs on one low-priority thread so startup tasks do
 * not compete with each other or delay the first frame.
 */
internal object NestSpaceDeferredStartup {

    private data class DispatchState(
        val context: Context,
        val startedAt: Long,
        val firstFrame: Boolean
    )

    private const val TAG = "NestSpaceStartup"
    private const val FALLBACK_DELAY_MS = 500L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "nestspace-deferred-startup").apply {
            isDaemon = true
        }
    }

    private data class PendingStartup(
        val context: Context,
        val startedAt: Long
    )

    private val pending = AtomicReference<PendingStartup?>(null)
    private val scheduled = AtomicBoolean(false)
    private val dispatched = AtomicBoolean(false)
    private val dispatchedOnFirstFrame = AtomicBoolean(false)

    private val fallback = Runnable { dispatch() }

    fun schedule(context: Context, startedAt: Long) {
        if (!scheduled.compareAndSet(false, true)) return
        pending.set(PendingStartup(context.applicationContext, startedAt))
        mainHandler.postDelayed(fallback, FALLBACK_DELAY_MS)
    }

    /** Dispatch on the next main-loop turn after this frame has been drawn. */
    fun afterFirstFrame(view: View) {
        if (isDispatched()) return
        view.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val observer = view.viewTreeObserver
                    if (observer.isAlive) {
                        observer.removeOnPreDrawListener(this)
                    }
                    view.post {
                        dispatchedOnFirstFrame.set(true)
                        dispatch()
                    }
                    return true
                }
            }
        )
    }

    private fun isDispatched(): Boolean = dispatched.get()

    private fun dispatch() {
        if (!dispatched.compareAndSet(false, true)) return
        val state = pending.get()?.let {
            DispatchState(
                it.context,
                it.startedAt,
                dispatchedOnFirstFrame.get()
            )
        } ?: return

        mainHandler.removeCallbacks(fallback)
        runCatching {
            FloatingDotOverlay.ensure(state.context)
        }.onFailure { Log.w(TAG, "Floating dot startup failed", it) }
        runCatching {
            BrowserProxyManager.apply(state.context)
        }.onFailure { Log.w(TAG, "Browser proxy startup failed", it) }
        runCatching {
            McpForegroundService.ensureStarted(state.context)
        }.onFailure { Log.w(TAG, "MCP service startup failed", it) }

        val source = if (state.firstFrame) "first-frame" else "fallback"
        worker.execute {
            runBackgroundWork(state.context, state.startedAt, source)
        }
    }

    private fun runBackgroundWork(context: Context, startedAt: Long, source: String) {
        AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_BACKGROUND)
        val started = SystemClock.elapsedRealtime()
        Log.d(TAG, "deferred startup begin source=$source")

        runCatching {
            RunRecovery.recover(context, startedBefore = startedAt)
        }.onFailure { Log.w(TAG, "Run recovery failed", it) }

        runCatching {
            if (PluginCenterStore.isEnabled(
                    context,
                    PluginCenterStore.SCHEDULED_TASKS
                )
            ) {
                ScheduledTaskManager.recoverInterrupted(
                    context,
                    startedBefore = startedAt
                )
                ScheduledTaskManager.rescheduleAll(context)
            }
        }.onFailure { Log.w(TAG, "Schedule recovery failed", it) }

        runCatching {
            McpClientManager.initialize(context)
        }.onFailure { Log.w(TAG, "MCP client initialization failed", it) }

        runCatching {
            val settings = FloatingDotSettings.load(context)
            if (LocalSpeechModelStore.isReady(context, settings.speechLanguage)) {
                LocalSpeechEngine.preload(context, settings.speechLanguage)
            }
        }.onFailure { Log.w(TAG, "Speech preload failed", it) }

        Log.d(
            TAG,
            "deferred startup end source=$source duration_ms=" +
                (SystemClock.elapsedRealtime() - started)
        )
    }
}

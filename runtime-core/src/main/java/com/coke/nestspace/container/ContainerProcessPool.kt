package com.coke.nestspace.container

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Assigns every running virtual app to a dedicated container process.
 *
 * Native libraries and the native bridge are process-global. Loading two
 * target apps into one process makes their shared libraries, JNI state and
 * translation state collide (on MuMu this shows up as EM_AARCH64/EM_X86_64
 * dlopen failures). Each slot is a real Android process (`:c1`..`:c16`); a slot
 * is killed before being handed to a different app, so no native state can
 * leak between apps or between two instances of the same app.
 *
 * Slot ownership is persisted so a main-process restart can still find the
 * process that was serving a session. If the process disappeared (native
 * crash, System.exit, LMK), a crash marker is written and the next launch
 * reports "restarted after crash" instead of silently resuming stale state.
 */
object ContainerProcessPool {

    private const val TAG = "ContainerProcessPool"
    const val SLOT_COUNT = 16
    private const val PREFS = "container_process_pool"
    private const val KILL_TIMEOUT_MS = 1_500L
    private const val KILL_WAIT_LIMIT_MS = KILL_TIMEOUT_MS + 500L
    private const val PSS_CACHE_MS = 1_500L
    private const val KEY_SLOT_LIMIT = "slot_limit"
    private const val KEY_AUTO_EFFECTIVE_LIMIT = "auto_effective_limit"
    private const val AUTO_LIMIT_REFRESH_MS = 2_000L
    const val AUTO_SLOT_LIMIT = -1
    const val DEFAULT_SLOT_LIMIT = AUTO_SLOT_LIMIT

    private val slotActivities: Array<Class<out StageAActivity>> = arrayOf(
        StageASlot1Activity::class.java,
        StageASlot2Activity::class.java,
        StageASlot3Activity::class.java,
        StageASlot4Activity::class.java,
        StageASlot5Activity::class.java,
        StageASlot6Activity::class.java,
        StageASlot7Activity::class.java,
        StageASlot8Activity::class.java,
        StageASlot9Activity::class.java,
        StageASlot10Activity::class.java,
        StageASlot11Activity::class.java,
        StageASlot12Activity::class.java,
        StageASlot13Activity::class.java,
        StageASlot14Activity::class.java,
        StageASlot15Activity::class.java,
        StageASlot16Activity::class.java
    )
    private val isolatedSlotActivities: Array<Class<out StageAActivity>> = arrayOf(
        StageAIsolatedSlot1Activity::class.java,
        StageAIsolatedSlot2Activity::class.java,
        StageAIsolatedSlot3Activity::class.java,
        StageAIsolatedSlot4Activity::class.java,
        StageAIsolatedSlot5Activity::class.java,
        StageAIsolatedSlot6Activity::class.java,
        StageAIsolatedSlot7Activity::class.java,
        StageAIsolatedSlot8Activity::class.java,
        StageAIsolatedSlot9Activity::class.java,
        StageAIsolatedSlot10Activity::class.java,
        StageAIsolatedSlot11Activity::class.java,
        StageAIsolatedSlot12Activity::class.java,
        StageAIsolatedSlot13Activity::class.java,
        StageAIsolatedSlot14Activity::class.java,
        StageAIsolatedSlot15Activity::class.java,
        StageAIsolatedSlot16Activity::class.java
    )
    private val slotProcessSuffixes = arrayOf(
        ":c1", ":c2", ":c3", ":c4", ":c5", ":c6", ":c7", ":c8",
        ":c9", ":c10", ":c11", ":c12", ":c13", ":c14", ":c15", ":c16"
    )

    private val slotState = ContainerSlotState(SLOT_COUNT)
    private val pssCache = ConcurrentHashMap<Int, PssSample>()
    private val maintenanceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "container-pool-maintenance").apply { isDaemon = true }
    }

    @Volatile
    private var processSlot: Int = -1

    /** Stored slot mode; Int.MIN_VALUE means "not loaded yet". */
    @Volatile
    private var configuredSlotMode: Int = Int.MIN_VALUE

    @Volatile
    private var autoEffectiveLimit: Int = SLOT_COUNT

    @Volatile
    private var autoSlotPssKb: Long = SlotMemoryPolicy.DEFAULT_SLOT_PSS_KB

    @Volatile
    private var autoLimitRefreshedAt: Long = 0L

    private data class PssSample(val sampledAt: Long, val memoryKb: Long)

    /** Slot of the host process this code runs in, or -1 in the main process. */
    fun currentSlot(): Int = processSlot

    /** Stored setting: [AUTO_SLOT_LIMIT] or an explicit slot count. */
    fun slotLimitMode(context: Context): Int {
        val cached = configuredSlotMode
        if (cached != Int.MIN_VALUE) return cached
        val saved = prefs(context).getInt(KEY_SLOT_LIMIT, DEFAULT_SLOT_LIMIT)
        val mode = if (saved == AUTO_SLOT_LIMIT) AUTO_SLOT_LIMIT
        else saved.coerceIn(1, SLOT_COUNT)
        configuredSlotMode = mode
        if (mode == AUTO_SLOT_LIMIT) {
            autoEffectiveLimit = prefs(context)
                .getInt(KEY_AUTO_EFFECTIVE_LIMIT, SLOT_COUNT)
                .coerceIn(1, SLOT_COUNT)
        }
        return mode
    }

    fun slotLimit(context: Context): Int {
        val mode = slotLimitMode(context)
        if (mode != AUTO_SLOT_LIMIT) return mode
        if (currentSlot() >= 0) {
            return prefs(context).getInt(KEY_AUTO_EFFECTIVE_LIMIT, SLOT_COUNT)
                .coerceIn(1, SLOT_COUNT)
        }
        return autoEffectiveLimit
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(context: Context, level: Int) {
        if (currentSlot() >= 0) return
        val pressure = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> true
            else -> false
        }
        val app = context.applicationContext
        maintenanceExecutor.execute {
            refreshAutoSlotLimit(
                context = app,
                enforce = true,
                samplePss = true,
                force = true,
                pressure = pressure
            )
        }
    }

    fun onLowMemory(context: Context) {
        if (currentSlot() >= 0) return
        val app = context.applicationContext
        maintenanceExecutor.execute {
            refreshAutoSlotLimit(
                context = app,
                enforce = true,
                samplePss = true,
                force = true,
                pressure = true
            )
        }
    }

    private fun refreshAutoSlotLimit(
        context: Context,
        protectedSlot: Int = -1,
        enforce: Boolean = false,
        samplePss: Boolean = false,
        force: Boolean = false,
        pressure: Boolean = false
    ) {
        if (currentSlot() >= 0 || slotLimitMode(context) != AUTO_SLOT_LIMIT) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - autoLimitRefreshedAt < AUTO_LIMIT_REFRESH_MS) return

        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val memory = ActivityManager.MemoryInfo()
        if (runCatching { activityManager.getMemoryInfo(memory) }.isFailure) return

        val runningSlots = slotState.slotCount()
        if (samplePss) {
            sampleAutoSlotPss(context)?.let { autoSlotPssKb = it }
        }
        val previous = autoEffectiveLimit
        val next = SlotMemoryPolicy.effectiveLimit(
            currentLimit = previous,
            runningSlots = runningSlots,
            availableKb = memory.availMem / 1024L,
            totalKb = memory.totalMem / 1024L,
            thresholdKb = memory.threshold / 1024L,
            lowMemory = memory.lowMemory || pressure,
            slotPssKb = autoSlotPssKb
        )
        autoEffectiveLimit = next
        autoLimitRefreshedAt = now
        if (force || next != previous) {
            prefs(context).edit().putInt(KEY_AUTO_EFFECTIVE_LIMIT, next).apply()
            Log.d(
                TAG,
                "auto slots=$next previous=$previous running=$runningSlots avail=${
                    memory.availMem / (1024L * 1024L)
                }MB threshold=${memory.threshold / (1024L * 1024L)}MB " +
                    "perSlot=${autoSlotPssKb / 1024L}MB low=${memory.lowMemory || pressure}"
            )
        }
        if (enforce) {
            evictAutoExcess(context, next, protectedSlot)
        }
    }

    private fun sampleAutoSlotPss(context: Context): Long? {
        val slots = slotState.liveSlots()
        if (slots.isEmpty()) return null
        val samples = slots.mapNotNull { slot ->
            markerPid(context, slot)?.let(::residentKb)?.takeIf { it > 0L }
        }
        return if (samples.isEmpty()) {
            null
        } else {
            SlotMemoryPolicy.estimateSlotPssKb(samples)
        }
    }

    private fun evictAutoExcess(context: Context, target: Int, protectedSlot: Int) {
        while (true) {
            val victim = slotState.detachAutoVictim(
                target = target,
                protectedSlot = protectedSlot,
                retainedSlots = retainedSlots()
            ) ?: return
            removeSessionState(context, victim.key)
            val stopped = killSlotProcess(context, victim.slot)
            slotState.finishKill(victim.slot, victim.generation, stopped)
            Log.d(TAG, "auto limit evicted ${victim.key} from slot ${victim.slot}")
        }
    }

    /**
     * Applies a new slot limit immediately. Assignments above the limit are
     * dropped and their processes killed, so a lowered limit takes effect
     * without restarting the host.
     */
    fun applySlotLimit(context: Context, limit: Int) {
        val mode = if (limit == AUTO_SLOT_LIMIT) AUTO_SLOT_LIMIT
        else limit.coerceIn(1, SLOT_COUNT)
        prefs(context).edit().putInt(KEY_SLOT_LIMIT, mode).apply()
        configuredSlotMode = mode
        if (mode == AUTO_SLOT_LIMIT) {
            refreshAutoSlotLimit(context, force = true, enforce = true, samplePss = true)
        } else {
            val removed = slotState.detachOverLimit(mode)
            removed.forEach { assignment ->
                removeSessionState(context, assignment.key)
                val stopped = killSlotProcess(context, assignment.slot)
                slotState.finishKill(assignment.slot, assignment.generation, stopped)
            }
            val preferences = prefs(context)
            val editor = preferences.edit()
            preferences.all.keys
                .filter { it.startsWith("session_slot_") }
                .forEach { key ->
                    if (preferences.getInt(key, -1) >= mode) editor.remove(key)
                }
            editor.apply()
            Log.d(TAG, "fixed slot limit=$mode removed=${removed.size}")
        }
    }

    fun markCurrentSlot(slot: Int) {
        processSlot = slot
    }

    /** Foreground host Activity; never evicted while it is resumed. */
    fun markActiveSlot(slot: Int) {
        slotState.markActive(slot)
    }

    fun clearActiveSlot(slot: Int) {
        slotState.clearActive(slot)
    }

    /**
     * Slot processes rename argv[0] to the target package, so /proc no longer
     * shows the manifest process name and getRunningAppProcesses() may omit
     * background container processes. Record the pid from inside the slot so
     * the main process can still detect and kill a stale owner.
     */
    fun markSlotProcess(context: Context, slot: Int) {
        runCatching {
            slotMarkerFile(context, slot).writeText(Process.myPid().toString())
        }.onFailure { Log.w(TAG, "write slot marker $slot failed", it) }
    }

    /** Slot of the most recently launched container, for cross-process control. */
    fun lastSlot(): Int? = slotState.lastSlot()

    fun slotForKey(context: Context, key: String): Int? {
        slotState.slotForKey(key)?.let { return it }
        return readPersistedSlot(context, key)
    }

    /**
     * Allocates a slot for a target subprocess. The calling process must be
     * the main NestSpace process so one owner performs eviction.
     */
    fun acquireProcess(context: Context, key: String, protectedSlot: Int = -1): Int {
        return acquire(context, key, protectedSlot)
    }

    /**
     * Slot hosting a specific package/instance. Omitted filters match the most
     * recently launched assignment.
     */
    fun slotFor(packageName: String? = null, instanceId: String? = null): Int? {
        return slotState.slotFor(packageName, instanceId)
    }

    data class SlotOwner(val packageName: String, val instanceId: String)

    /** Package/instance assigned to a slot, for recovery when callers omit them. */
    fun ownerOfSlot(slot: Int): SlotOwner? {
        val owner = slotState.ownerOfSlot(slot) ?: return null
        return SlotOwner(owner.first, owner.second)
    }

    /**
     * Resolve a live slot from its process id. Slot processes rename argv[0],
     * so callers cannot rely on the manifest process name after target startup.
     */
    fun slotForPid(context: Context, pid: Int): Int? {
        if (pid <= 0) return null
        (0 until SLOT_COUNT).firstOrNull { slot -> markerPid(context, slot) == pid }
            ?.let { return it }
        context.getSystemService(ActivityManager::class.java)
            ?.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
            ?.let { processName ->
                slotProcessSuffixes.indexOfFirst {
                    context.packageName + it == processName
                }.takeIf { it >= 0 }?.let { return it }
            }
        val processPids = processPidsByName()
        return (0 until SLOT_COUNT).firstOrNull { slot ->
            processPids[context.packageName + slotProcessSuffixes[slot]] == pid
        }
    }

    fun acquire(context: Context, key: String, protectedSlot: Int = -1): Int {
        refreshAutoSlotLimit(
            context = context,
            protectedSlot = protectedSlot,
            enforce = true,
            samplePss = true
        )
        val limit = slotLimit(context)
        evictBackgroundForMemory(context, limit, protectedSlot)
        val preferredSlot = readPersistedSlot(context, key) ?: -1
        val waitDeadline = SystemClock.uptimeMillis() + KILL_WAIT_LIMIT_MS

        while (true) {
            when (
                val plan = slotState.planAcquire(
                    key = key,
                    limit = limit,
                    protectedSlot = protectedSlot,
                    preferredSlot = preferredSlot,
                    retainedSlots = retainedSlots()
                )
            ) {
                is ContainerSlotState.AcquirePlan.Existing -> {
                    reportIfDied(context, prefs(context), key, plan.slot)
                    if (slotState.confirmExisting(key, plan.slot, plan.generation)) {
                        Log.d(TAG, "reuse $key -> slot ${plan.slot}")
                        return plan.slot
                    }
                }

                is ContainerSlotState.AcquirePlan.Reuse -> {
                    val lease = plan.lease
                    // The slot stays KILLING and reserved while this blocking
                    // work runs; the global state lock is no longer held.
                    slotState.beginKill(lease)
                    val stopped = killSlotProcess(context, lease.slot)
                    if (!stopped) {
                        slotState.abortAcquire(lease)
                        error("container slot ${lease.slot} is still alive after kill")
                    }
                    lease.previousKey?.let { removeSessionState(context, it) }
                    if (!slotState.commitAcquire(lease)) {
                        error("container slot ${lease.slot} changed while it was being prepared")
                    }
                    persistSessionSlot(context, key, lease.slot)
                    Log.d(TAG, "assign $key -> slot ${lease.slot}")
                    return lease.slot
                }

                ContainerSlotState.AcquirePlan.Wait -> {
                    val remaining = waitDeadline - SystemClock.uptimeMillis()
                    if (remaining <= 0L) error("timed out waiting for slot assignment: $key")
                    slotState.waitForReservation(key, remaining.coerceAtMost(100L))
                }

                ContainerSlotState.AcquirePlan.Full -> {
                    error("container process pool is empty")
                }
            }
        }
    }

    /**
     * Cheap desktop-launch lookup. This intentionally does not query
     * ActivityManager, /proc or PSS; bringToFront/engine.launch perform their
     * own worker-thread validation when needed.
     */
    fun findRunningSlot(packageName: String, instanceId: String? = null): RunningSlotRef? {
        val assignment = slotState.findRunningSlot(packageName, instanceId) ?: return null
        return RunningSlotRef(
            key = assignment.key,
            slot = assignment.slot,
            instanceId = instanceOf(assignment.key)
        )
    }

    data class RunningSlotRef(
        val key: String,
        val slot: Int,
        val instanceId: String
    )

    data class RunningSlot(
        val key: String,
        val slot: Int,
        val packageName: String,
        val instanceId: String,
        val processName: String,
        val active: Boolean,
        val memoryKb: Long
    )

    /** Snapshot of the live assignments for the running-apps screen. */
    fun runningSlots(context: Context): List<RunningSlot> {
        val snapshot = slotState.liveAssignments()
        if (snapshot.isEmpty()) return emptyList()
        val active = slotState.activeSlot()
        val running = context.getSystemService(ActivityManager::class.java)
            ?.runningAppProcesses
            .orEmpty()
            .associateBy { it.processName }
        var processIndexCache: Map<String, Int>? = null
        val preferences = prefs(context)
        val result = snapshot
            .mapNotNull { assignment ->
                val key = assignment.key
                val slot = assignment.slot
                val packageName = key.substringBefore('@')
                val instanceId = key.substringAfter('@', "").substringBefore('|')
                val processName = if (key.contains('|')) key.substringAfter('|') else packageName
                val manifestProcessName = context.packageName + slotProcessSuffixes[slot]
                val info = running[manifestProcessName]
                val processIndex = processIndexCache
                    ?: processPidsByName().also { processIndexCache = it }
                val pid = info?.pid ?: processIndex[manifestProcessName]
                    ?: markerPid(context, slot)
                if (pid == null) {
                    // A dead slot must not linger in Running apps. Record the
                    // crash once, then hide it until a launch reuses the slot.
                    if (preferences.getBoolean(launchedKey(key), false)) {
                        recordDied(context, preferences, key, slot)
                    }
                    slotState.recordDead(assignment)
                    return@mapNotNull null
                }
                RunningSlot(
                    key = key,
                    slot = slot,
                    packageName = packageName,
                    instanceId = instanceId,
                    processName = processName,
                    active = slot == active ||
                        info?.importance ==
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                    memoryKb = residentKb(pid)
                )
            }
            .filter { slotState.isCurrent(it.key, it.slot) }
            .sortedBy { it.slot }
        return result
    }

    /**
     * Bring a running container slot to the foreground for user takeover.
     * Reuses the existing host activity so the target app keeps its current
     * page; the host clears its headless flag in onNewIntent.
     */
    fun bringToFront(
        context: Context,
        slot: Int,
        packageName: String = "",
        instanceId: String = ""
    ): Boolean {
        if (slot !in 0 until SLOT_COUNT) return false
        if (!isProcessAlive(context, slot)) return false
        val intent = Intent(context, slotActivities[slot]).apply {
            action = StageAActivity.ACTION_FOREGROUND
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            if (packageName.isNotBlank()) {
                putExtra(StageAActivity.EXTRA_LAUNCH_PACKAGE, packageName)
                putExtra(StageAActivity.EXTRA_LAUNCH_INSTANCE, instanceId)
            }
            putExtra(StageAActivity.EXTRA_HEADLESS, false)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, "bring slot $slot to front failed", it)
            false
        }
    }

    /** Closes one running slot and frees its memory; data stays on disk. */
    fun closeSlot(context: Context, slot: Int): Boolean {
        val removed = slotState.detachSlot(slot) ?: return false
        removeSessionState(context, removed.key)
        val stopped = killSlotProcess(context, removed.slot)
        slotState.finishKill(removed.slot, removed.generation, stopped)
        Log.d(TAG, "close ${removed.key} from slot ${removed.slot}")
        return true
    }

    private fun processPid(context: Context, slot: Int): Int? {
        val processName = context.packageName + slotProcessSuffixes[slot]
        return context.getSystemService(ActivityManager::class.java)
            ?.runningAppProcesses
            ?.firstOrNull { it.processName == processName }
            ?.pid
            ?: pidOfProcess(processName)
            ?: markerPid(context, slot)
    }

    /**
     * PSS (proportional set size) from smaps_rollup: shared pages are split
     * between the processes that map them, so per-app totals are much closer
     * to real physical memory than VmRSS. Fall back to VmRSS when the kernel
     * or SELinux does not expose smaps_rollup.
     */
    private fun residentKb(pid: Int): Long {
        val now = SystemClock.elapsedRealtime()
        pssCache[pid]?.takeIf { now - it.sampledAt <= PSS_CACHE_MS }?.let {
            return it.memoryKb
        }
        val pss = runCatching {
            File("/proc/$pid/smaps_rollup").useLines { lines ->
                lines.firstOrNull { it.startsWith("Pss:") }
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toLongOrNull()
            }
        }.getOrNull()
        val memoryKb = if (pss != null && pss > 0) {
            pss
        } else runCatching {
            File("/proc/$pid/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toLongOrNull()
            } ?: 0L
        }.getOrDefault(0L)
        pssCache[pid] = PssSample(now, memoryKb)
        return memoryKb
    }

    /** Marks that the slot process was actually started for this session. */
    fun markLaunched(context: Context, key: String) {
        prefs(context).edit().putBoolean(launchedKey(key), true).apply()
    }

    /**
     * Drops a session and all of its target-subprocess sessions. Used by
     * uninstall/update so main and `:remote` processes do not survive with
     * stale code or deleted data.
     */
    fun forgetPrefix(context: Context, prefix: String) {
        val removed = slotState.detachKeys { key ->
            key == prefix || key.startsWith("$prefix|")
        }
        removed.forEach { assignment ->
            stopAndFinish(context, assignment)
            clearCrashState(context, assignment.key)
        }
        val preferences = prefs(context)
        val editor = preferences.edit()
        preferences.all.keys
            .filter { raw ->
                val key = raw.removePrefix("launched_").removePrefix("session_slot_")
                key == prefix || key.startsWith("$prefix|")
            }
            .forEach(editor::remove)
        editor.apply()
    }

    /** Stop every process/instance of a package without deleting its data. */
    fun closePackage(context: Context, packageName: String): Int {
        val removed = slotState.detachKeys { key ->
            key.substringBefore('@') == packageName
        }
        removed.forEach { stopAndFinish(context, it) }
        return removed.size
    }

    /**
     * Kills every known process belonging to one package instance.
     *
     * Do not filter through [runningSlots] first: a hardened app can rename
     * argv[0] or be in a native crash, which makes liveness detection miss a
     * still-live slot and permanently drop its bookkeeping before it is killed.
     */
    fun closeInstance(context: Context, packageName: String, instanceId: String): List<Int> {
        val prefix = "$packageName@$instanceId"
        val removed = slotState.detachKeys { key ->
            key == prefix || key.startsWith("$prefix|")
        }
        removed.forEach { assignment ->
            stopAndFinish(context, assignment)
            clearCrashState(context, assignment.key)
        }
        val preferences = prefs(context)
        val editor = preferences.edit()
        preferences.all.keys
            .filter { raw ->
                val key = raw.removePrefix("launched_").removePrefix("session_slot_")
                key == prefix || key.startsWith("$prefix|")
            }
            .forEach(editor::remove)
        editor.apply()
        return removed.map { it.slot }
    }

    /**
     * Drops a session's slot bookkeeping. Used by uninstall so a later launch
     * of the same package cannot be reported as recovering from a crash.
     */
    fun forget(context: Context, key: String) {
        val removed = slotState.detachKey(key)
        removed?.let { stopAndFinish(context, it) }
        prefs(context).edit()
            .remove(launchedKey(key))
            .remove(sessionSlotKey(key))
            .apply()
        clearCrashState(context, key)
    }

    /**
     * A main-process cold start means the host was updated, force-stopped or
     * killed by the system; child slot processes are gone for reasons that are
     * not target-app crashes. Clear only the "was launched" flags; real crash
     * markers written by TargetCrashGuard are files and stay.
     */
    fun resetLaunchedFlagsAfterHostRestart(context: Context) {
        val prefs = prefs(context)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("launched_") }.forEach(editor::remove)
        editor.apply()
    }

    fun activityClass(slot: Int): Class<out StageAActivity> = slotActivities[slot]

    fun isolatedActivityClass(slot: Int): Class<out StageAActivity> =
        isolatedSlotActivities[slot]

    fun isIsolatedActivity(activityClass: Class<*>): Boolean =
        isolatedSlotActivities.any { it == activityClass }

    /**
     * Kills the least recently used assignment that is not the foreground app
     * and not the caller's own slot. Returns the freed slot index.
     */
    private fun evictOldest(
        context: Context,
        limit: Int,
        protectedSlot: Int,
        protectActive: Boolean,
        subprocessOnly: Boolean,
        reason: String
    ): Int? {
        val victim = slotState.detachEvictionVictim(
            limit = limit,
            protectedSlot = protectedSlot,
            protectActive = protectActive,
            subprocessOnly = subprocessOnly,
            retainedSlots = retainedSlots()
        ) ?: return null
        // Reuse must not surface as a crash of the evicted app.
        removeSessionState(context, victim.key)
        val stopped = killSlotProcess(context, victim.slot)
        slotState.finishKill(victim.slot, victim.generation, stopped)
        Log.d(TAG, "evict ${victim.key} from slot ${victim.slot} ($reason)")
        return victim.slot
    }

    /**
     * Before allocating, drop up to two old background sessions when the
     * system reports memory pressure. This keeps the new app's process from
     * being killed immediately and frees memory for the active session.
     */
    private fun evictBackgroundForMemory(
        context: Context,
        limit: Int,
        protectedSlot: Int
    ) {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val info = ActivityManager.MemoryInfo()
        runCatching { activityManager.getMemoryInfo(info) }.getOrElse { return }
        val threshold = maxOf(info.threshold, 128L * 1024L * 1024L)
        if (!info.lowMemory && info.availMem >= threshold * 2) return
        var evicted = 0
        while (evicted < 2) {
            val slot = evictOldest(
                context, limit, protectedSlot,
                protectActive = true, subprocessOnly = true, reason = "memory"
            ) ?: if (evicted == 0) {
                // No subprocess left: give up one background main process.
                evictOldest(
                    context, limit, protectedSlot,
                    protectActive = true, subprocessOnly = false, reason = "memory"
                )
            } else {
                null
            }
            if (slot == null) break
            evicted++
        }
        if (evicted > 0) {
            Log.d(TAG, "memory pressure: evicted $evicted background slot(s)")
        }
    }

    private fun reportIfDied(
        context: Context,
        prefs: SharedPreferences,
        key: String,
        slot: Int
    ) {
        if (!prefs.getBoolean(launchedKey(key), false)) return
        if (isProcessAlive(context, slot)) return
        recordDied(context, prefs, key, slot)
    }

    fun isProcessAlive(context: Context, slot: Int): Boolean {
        return processPid(context, slot) != null
    }

    private fun slotMarkerFile(context: Context, slot: Int): File =
        File(context.applicationContext.filesDir, "slot_owner_$slot.pid")

    private fun markerPid(context: Context, slot: Int): Int? {
        val pid = runCatching {
            slotMarkerFile(context, slot).readText().trim().toIntOrNull()
        }.getOrNull() ?: return null
        if (pid <= 0 || pid == Process.myPid()) return null
        // Guard against pid reuse after the slot died.
        val status = runCatching { File("/proc/$pid/status").readText() }.getOrNull()
            ?: return null
        val uid = status.lineSequence()
            .firstOrNull { it.startsWith("Uid:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toIntOrNull()
        return pid.takeIf { uid == Process.myUid() }
    }

    private fun pidOfProcess(processName: String): Int? {
        return processPidsByName()[processName]
    }

    private fun processPidsByName(): Map<String, Int> {
        val dirs = File("/proc").listFiles() ?: return emptyMap()
        return buildMap {
            dirs.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                if (!dir.isDirectory) return@forEach
                val processName = runCatching {
                    File(dir, "cmdline").readText().trim('\u0000')
                }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@forEach
                putIfAbsent(processName, pid)
            }
        }
    }

    private fun killSlotProcess(context: Context, slot: Int): Boolean {
        // The main process may hold a keep-alive UI-service binding for a
        // headless slot. Release it before killing/reusing the process.
        if (currentSlot() < 0) {
            runCatching {
                com.coke.nestspace.control.ContainerUiKeepAlive.unbind(context, slot)
            }
        }
        val processName = context.packageName + slotProcessSuffixes[slot]
        // ActivityManager still tracks the manifest process name after the
        // container renames argv[0] to the target package.
        val pid = context.getSystemService(ActivityManager::class.java)
            ?.runningAppProcesses
            ?.firstOrNull { it.processName == processName }
            ?.pid
            ?: pidOfProcess(processName)
            ?: markerPid(context, slot)
            ?: return true
        if (pid == Process.myPid()) return false

        Log.d(TAG, "reset process $processName (pid=$pid)")
        Process.killProcess(pid)
        val deadline = SystemClock.uptimeMillis() + KILL_TIMEOUT_MS
        var delayMs = 25L
        while (SystemClock.uptimeMillis() < deadline) {
            if (!sameUidPidExists(pid)) {
                pssCache.remove(pid)
                return true
            }
            try {
                Thread.sleep(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(200L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        val stopped = !sameUidPidExists(pid)
        if (stopped) pssCache.remove(pid)
        if (!stopped) Log.w(TAG, "process $processName is still alive after kill")
        return stopped
    }

    private fun sameUidPidExists(pid: Int): Boolean {
        if (pid <= 0) return false
        val status = runCatching { File("/proc/$pid/status").readText() }.getOrNull()
            ?: return false
        val uid = status.lineSequence()
            .firstOrNull { it.startsWith("Uid:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toIntOrNull()
        return uid == Process.myUid()
    }

    private fun stopAndFinish(
        context: Context,
        assignment: ContainerSlotState.RemovedAssignment
    ) {
        removeSessionState(context, assignment.key)
        val stopped = killSlotProcess(context, assignment.slot)
        slotState.finishKill(assignment.slot, assignment.generation, stopped)
    }

    private fun recordDied(
        context: Context,
        prefs: SharedPreferences,
        key: String,
        slot: Int
    ) {
        val packageName = key.substringBefore('@')
        val instanceId = instanceOf(key)
        ContainerCrashStore.record(
            context,
            packageName,
            instanceId,
            "container process :c${slot + 1} died (native crash or exit)"
        )
        prefs.edit().remove(launchedKey(key)).apply()
    }

    private fun clearCrashState(context: Context, key: String) {
        ContainerCrashStore.clear(context, key.substringBefore('@'), instanceOf(key))
    }

    private fun removeSessionState(context: Context, key: String) {
        prefs(context).edit()
            .remove(launchedKey(key))
            .remove(sessionSlotKey(key))
            .apply()
    }

    private fun persistSessionSlot(context: Context, key: String, slot: Int) {
        prefs(context).edit().putInt(sessionSlotKey(key), slot).apply()
    }

    private fun readPersistedSlot(context: Context, key: String): Int? {
        return prefs(context).getInt(sessionSlotKey(key), -1)
            .takeIf { it in 0 until SLOT_COUNT }
    }

    private fun retainedSlots(): Set<Int> =
        (0 until SLOT_COUNT)
            .filterTo(linkedSetOf()) {
                com.coke.nestspace.control.ContainerUiKeepAlive.isRetained(it)
            }

    private fun instanceOf(key: String): String =
        key.substringAfter('@', "instance-1").substringBefore('|')

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun launchedKey(key: String): String = "launched_$key"

    private fun sessionSlotKey(key: String): String = "session_slot_$key"
}

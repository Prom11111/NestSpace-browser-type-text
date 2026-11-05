package com.coke.nestspace.container

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File

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
    private const val KEY_SLOT_LIMIT = "slot_limit"
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
    private val slotProcessSuffixes = arrayOf(
        ":c1", ":c2", ":c3", ":c4", ":c5", ":c6", ":c7", ":c8",
        ":c9", ":c10", ":c11", ":c12", ":c13", ":c14", ":c15", ":c16"
    )

    /** Access-ordered so the least recently launched app is evicted first. */
    private val assignments = java.util.LinkedHashMap<String, Int>(8, 0.75f, true)
    private val owners = arrayOfNulls<String>(SLOT_COUNT)

    @Volatile
    private var lastAcquiredSlot: Int = -1

    @Volatile
    private var processSlot: Int = -1

    /** Slot hosting the currently resumed host Activity, or -1. */
    @Volatile
    private var activeSlot: Int = -1

    /** Stored slot mode; Int.MIN_VALUE means "not loaded yet". */
    @Volatile
    private var configuredSlotMode: Int = Int.MIN_VALUE

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
        return mode
    }

    /**
     * Effective number of slot processes that may be used right now.
     *
     * Auto mode allows the full pool and relies on dynamic memory-pressure
     * eviction: new sessions may use any of the slots, and when RAM runs low
     * the least recently used background processes are closed first.
     */
    fun slotLimit(context: Context): Int {
        val mode = slotLimitMode(context)
        return if (mode == AUTO_SLOT_LIMIT) SLOT_COUNT else mode
    }

    /**
     * Applies a new slot limit immediately. Assignments above the limit are
     * dropped and their processes killed, so a lowered limit takes effect
     * without restarting the host.
     */
    @Synchronized
    fun applySlotLimit(context: Context, limit: Int) {
        val mode = if (limit == AUTO_SLOT_LIMIT) AUTO_SLOT_LIMIT
        else limit.coerceIn(1, SLOT_COUNT)
        prefs(context).edit().putInt(KEY_SLOT_LIMIT, mode).apply()
        configuredSlotMode = mode
        val value = if (mode == AUTO_SLOT_LIMIT) SLOT_COUNT else mode
        if (lastAcquiredSlot >= value) lastAcquiredSlot = -1
        val removed = assignments.entries.filter { it.value >= value }.map { it.key }
        removed.forEach { forget(context, it) }
        val preferences = prefs(context)
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith("session_slot_") }
            .forEach { key ->
                if (preferences.getInt(key, -1) >= value) editor.remove(key)
            }
        editor.apply()
        Log.d(TAG, "slot limit mode=$mode effective=$value")
    }

    fun markCurrentSlot(slot: Int) {
        processSlot = slot
    }

    /** Foreground host Activity; never evicted while it is resumed. */
    fun markActiveSlot(slot: Int) {
        activeSlot = slot
    }

    fun clearActiveSlot(slot: Int) {
        if (activeSlot == slot) activeSlot = -1
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
    fun lastSlot(): Int? = lastAcquiredSlot.takeIf { it in 0 until SLOT_COUNT }

    @Synchronized
    fun slotForKey(context: Context, key: String): Int? {
        val limit = slotLimit(context)
        assignments[key]?.takeIf { it < limit }?.let { return it }
        val persisted = prefs(context).getInt(sessionSlotKey(key), -1)
        return persisted.takeIf { it in 0 until limit }
    }

    /**
     * Allocates a slot for a target subprocess. The calling process must be
     * the main NestSpace process so one owner performs eviction.
     */
    @Synchronized
    fun acquireProcess(context: Context, key: String, protectedSlot: Int = -1): Int {
        val slot = acquire(context, key, protectedSlot)
        prefs(context).edit().putInt(sessionSlotKey(key), slot).apply()
        return slot
    }

    /**
     * Slot hosting a specific package/instance. Omitted filters match the most
     * recently launched assignment.
     */
    @Synchronized
    fun slotFor(packageName: String? = null, instanceId: String? = null): Int? {
        if (packageName == null && instanceId == null) return lastSlot()
        return assignments.entries.lastOrNull { (key, _) ->
            if (key.contains('|')) return@lastOrNull false
            val assignedPackage = key.substringBefore('@')
            val assignedInstance = instanceOf(key)
            (packageName == null || assignedPackage == packageName) &&
                (instanceId == null || assignedInstance == instanceId)
        }?.value
    }

    data class SlotOwner(val packageName: String, val instanceId: String)

    /** Package/instance assigned to a slot, for recovery when callers omit them. */
    @Synchronized
    fun ownerOfSlot(slot: Int): SlotOwner? {
        val entry = assignments.entries.firstOrNull { it.value == slot } ?: return null
        if (entry.key.contains('|')) return null
        return SlotOwner(entry.key.substringBefore('@'), instanceOf(entry.key))
    }

    @Synchronized
    fun acquire(context: Context, key: String, protectedSlot: Int = -1): Int {
        val prefs = prefs(context)
        val limit = slotLimit(context)
        assignments[key]?.let { slot ->
            if (slot < limit) {
                reportIfDied(context, prefs, key, slot)
                lastAcquiredSlot = slot
                return slot
            }
            // The limit was lowered without going through applySlotLimit.
            assignments.remove(key)
            if (owners.getOrNull(slot) == key) owners[slot] = null
        }

        // Free old background sessions before they can push the process over
        // the memory limit. Never evict the session we are about to use.
        evictBackgroundForMemory(context, limit, protectedSlot)
        var slot = reusableSlot(context, prefs, key, limit)
        if (slot < 0) slot = (0 until limit).firstOrNull { owners[it] == null } ?: -1
        if (slot < 0) {
            // Slot pressure: background subprocesses are expendable first, so
            // app main processes stay alive and more apps can remain runnable.
            // Only fall back to a main process, then to the foreground/caller
            // slot, when nothing else is available.
            slot = evictOldest(
                context, limit, protectedSlot,
                protectActive = true, subprocessOnly = true, reason = "slots"
            ) ?: evictOldest(
                context, limit, protectedSlot,
                protectActive = true, subprocessOnly = false, reason = "slots"
            ) ?: evictOldest(
                context, limit, protectedSlot,
                protectActive = false, subprocessOnly = false, reason = "slots"
            ) ?: error("container process pool is empty")
        }

        // A slot may still be alive with the previous app's native libraries.
        // Kill it before handing the slot to a different app.
        killSlotProcess(context, slot)
        owners[slot] = key
        assignments[key] = slot
        lastAcquiredSlot = slot
        prefs.edit()
            .putInt(sessionSlotKey(key), slot)
            .apply()
        Log.d(TAG, "assign $key -> slot $slot")
        return slot
    }

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
    @Synchronized
    fun runningSlots(context: Context): List<RunningSlot> {
        val limit = slotLimit(context)
        val running = context.getSystemService(ActivityManager::class.java)
            ?.runningAppProcesses
            .orEmpty()
            .associateBy { it.processName }
        val preferences = prefs(context)
        val entries = assignments.entries.map { it.key to it.value }
        return entries
            .filter { (_, slot) -> slot in 0 until limit }
            .mapNotNull { (key, slot) ->
                val packageName = key.substringBefore('@')
                val instanceId = key.substringAfter('@', "").substringBefore('|')
                val processName = if (key.contains('|')) key.substringAfter('|') else packageName
                val info = running[context.packageName + slotProcessSuffixes[slot]]
                val pid = info?.pid ?: pidOfProcess(context.packageName + slotProcessSuffixes[slot])
                    ?: markerPid(context, slot)
                if (pid == null) {
                    // A dead slot must not linger in Running apps. Record the
                    // crash once, then hide it until a launch reuses the slot.
                    if (preferences.getBoolean(launchedKey(key), false)) {
                        reportIfDied(context, preferences, key, slot)
                    }
                    return@mapNotNull null
                }
                RunningSlot(
                    key = key,
                    slot = slot,
                    packageName = packageName,
                    instanceId = instanceId,
                    processName = processName,
                    active = slot == activeSlot ||
                        info?.importance ==
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                    memoryKb = residentKb(pid)
                )
            }
            .sortedBy { it.slot }
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
    @Synchronized
    fun closeSlot(context: Context, slot: Int): Boolean {
        val entry = assignments.entries.firstOrNull { it.value == slot } ?: return false
        assignments.remove(entry.key)
        if (owners.getOrNull(slot) == entry.key) owners[slot] = null
        prefs(context).edit()
            .remove(launchedKey(entry.key))
            .remove(sessionSlotKey(entry.key))
            .apply()
        if (activeSlot == slot) activeSlot = -1
        if (lastAcquiredSlot == slot) lastAcquiredSlot = -1
        killSlotProcess(context, slot)
        Log.d(TAG, "close ${entry.key} from slot $slot")
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
        val pss = runCatching {
            File("/proc/$pid/smaps_rollup").useLines { lines ->
                lines.firstOrNull { it.startsWith("Pss:") }
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toLongOrNull()
            }
        }.getOrNull()
        if (pss != null && pss > 0) return pss
        return runCatching {
            File("/proc/$pid/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toLongOrNull()
            } ?: 0L
        }.getOrDefault(0L)
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
    @Synchronized
    fun forgetPrefix(context: Context, prefix: String) {
        val keys = assignments.keys
            .filter { it == prefix || it.startsWith("$prefix|") }
            .toList()
        keys.forEach { forget(context, it) }
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
    @Synchronized
    fun closePackage(context: Context, packageName: String): Int {
        val keys = assignments.keys
            .filter { it.substringBefore('@') == packageName }
            .toList()
        keys.forEach { forget(context, it) }
        return keys.size
    }

    /**
     * Drops a session's slot bookkeeping. Used by uninstall so a later launch
     * of the same package cannot be reported as recovering from a crash.
     */
    @Synchronized
    fun forget(context: Context, key: String) {
        val slot = assignments.remove(key)
        if (slot != null && owners.getOrNull(slot) == key) {
            owners[slot] = null
            killSlotProcess(context, slot)
        }
        prefs(context).edit()
            .remove(launchedKey(key))
            .remove(sessionSlotKey(key))
            .apply()
        val packageName = key.substringBefore('@')
        val instanceId = instanceOf(key)
        ContainerCrashStore.clear(context, packageName, instanceId)
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

    /**
     * Kills the least recently used assignment that is not the foreground app
     * and not the caller's own slot. Returns the freed slot index.
     */
    @Synchronized
    private fun evictOldest(
        context: Context,
        limit: Int,
        protectedSlot: Int,
        protectActive: Boolean,
        subprocessOnly: Boolean,
        reason: String
    ): Int? {
        val candidate = assignments.entries.firstOrNull { (key, slot) ->
            slot < limit &&
                slot != protectedSlot &&
                (!protectActive || slot != activeSlot) &&
                (!subprocessOnly || key.contains('|'))
        } ?: return null
        val slot = candidate.value
        assignments.remove(candidate.key)
        if (owners.getOrNull(slot) == candidate.key) owners[slot] = null
        // Reuse must not surface as a crash of the evicted app.
        prefs(context).edit()
            .remove(launchedKey(candidate.key))
            .remove(sessionSlotKey(candidate.key))
            .apply()
        killSlotProcess(context, slot)
        Log.d(TAG, "evict ${candidate.key} from slot $slot ($reason)")
        return slot
    }

    /**
     * Before allocating, drop up to two old background sessions when the
     * system reports memory pressure. This keeps the new app's process from
     * being killed immediately and frees memory for the active session.
     */
    @Synchronized
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

    private fun reusableSlot(
        context: Context,
        prefs: SharedPreferences,
        key: String,
        limit: Int
    ): Int {
        val previous = prefs.getInt(sessionSlotKey(key), -1)
        if (previous !in 0 until limit) return -1
        val owner = owners[previous]
        if (owner != null && owner != key) return -1
        if (owner == null) {
            // No live assignment in this process, but /proc may still expose
            // the slot from before a main-process restart. Kill it so native
            // libraries from that previous app cannot be reused.
            reportIfDied(context, prefs, key, previous)
            if (isProcessAlive(context, previous)) killSlotProcess(context, previous)
        }
        return previous
    }

    private fun reportIfDied(
        context: Context,
        prefs: SharedPreferences,
        key: String,
        slot: Int
    ) {
        if (!prefs.getBoolean(launchedKey(key), false)) return
        if (isProcessAlive(context, slot)) return
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

    fun isProcessAlive(context: Context, slot: Int): Boolean {
        val processName = context.packageName + slotProcessSuffixes[slot]
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val running = activityManager?.runningAppProcesses
        if (running?.any { it.processName == processName } == true) return true
        return pidOfProcess(processName) != null || markerPid(context, slot) != null
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
        val dirs = File("/proc").listFiles() ?: return null
        return dirs.firstNotNullOfOrNull { dir ->
            if (!dir.isDirectory || dir.name.toIntOrNull() == null) return@firstNotNullOfOrNull null
            val cmdline = runCatching {
                File(dir, "cmdline").readText().trim('\u0000')
            }.getOrNull()
            if (cmdline == processName) dir.name.toInt() else null
        }
    }

    private fun killSlotProcess(context: Context, slot: Int) {
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
            ?: return
        if (pid == Process.myPid()) return

        Log.d(TAG, "reset process $processName (pid=$pid)")
        Process.killProcess(pid)
        val deadline = SystemClock.uptimeMillis() + KILL_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (!isProcessAlive(context, slot)) return
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        Log.w(TAG, "process $processName is still alive after kill")
    }

    private fun instanceOf(key: String): String =
        key.substringAfter('@', "instance-1").substringBefore('|')

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun launchedKey(key: String): String = "launched_$key"

    private fun sessionSlotKey(key: String): String = "session_slot_$key"
}

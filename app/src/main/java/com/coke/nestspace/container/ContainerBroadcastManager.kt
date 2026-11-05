package com.coke.nestspace.container

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Local BroadcastReceiver host for one ContainerSession.
 *
 * Manifest receivers are instantiated on demand from the target APK. Dynamic
 * receivers are registered by target Contexts through ContainerContext and are
 * matched with the same platform IntentFilter implementation used for
 * Activities/Services.
 *
 * Host/system broadcasts are bridged in both directions. Actions registered by
 * target Receivers (manifest or dynamic) are also registered on the host
 * application context; broadcasts the system sends to NestSpace are then
 * matched and delivered to the target receivers. Broadcasts the target sends
 * without targeting its own package are still forwarded to the host after
 * local delivery, with an origin marker so the bridge does not echo them back.
 */
class ContainerBroadcastManager(
    private val session: ContainerSession
) {

    private data class DynamicRecord(
        val receiver: BroadcastReceiver,
        val filter: IntentFilter,
        val permission: String?,
        val scheduler: Handler?,
        val flags: Int
    )

    private sealed interface Target {
        val priority: Int

        data class Static(
            val info: android.content.pm.ActivityInfo,
            override val priority: Int
        ) : Target

        data class Dynamic(
            val record: DynamicRecord,
            override val priority: Int
        ) : Target
    }

    private data class ResultState(
        var code: Int,
        var data: String?,
        var extras: Bundle?,
        var aborted: Boolean = false
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dynamicRecords = mutableListOf<DynamicRecord>()
    private val stickyIntents = mutableListOf<Intent>()

    private val hostContext: Context = session.hostContext
    private val bridgedActions = linkedSetOf<String>()
    private var hostReceiver: BroadcastReceiver? = null

    /**
     * Registers the host-side bridge for every action the target declared in
     * its manifest Receivers. Dynamic registrations extend it on the fly.
     */
    fun startHostBridge() {
        val actions = session.spec.receiverFilters.values
            .flatten()
            .flatMap { it.actions }
        ensureBridgedActions(actions)
    }

    private fun ensureBridgedActions(actions: Collection<String>) {
        val newActions = synchronized(bridgedActions) {
            actions.filter { it.isNotBlank() && bridgedActions.add(it) }
        }
        if (newActions.isEmpty()) return
        val receiver = hostReceiver ?: object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                // Do not echo broadcasts the container itself forwarded to the
                // host; they were already delivered locally.
                val origin = intent.getStringExtra(EXTRA_BRIDGE_ORIGIN)
                if (origin == session.spec.packageName) return
                deliverFromHost(Intent(intent))
            }
        }.also { hostReceiver = it }

        // One registration per action. Re-registering the receiver with a
        // growing filter would replay every sticky broadcast each time a new
        // action is added.
        newActions.forEach { action ->
            val filter = IntentFilter(action)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hostContext.registerReceiver(
                        receiver,
                        filter,
                        HOST_BRIDGE_PERMISSION,
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    hostContext.registerReceiver(receiver, filter, HOST_BRIDGE_PERMISSION, null)
                }
            }.onFailure {
                Log.w(TAG, "register host bridge action failed: $action", it)
            }
        }
        Log.d(TAG, "host bridge actions=${newActions.size} total=${bridgedActions.size}")
    }

    private fun deliverFromHost(intent: Intent) {
        val snapshot = Intent(intent)
        mainHandler.post {
            if (isStickyAction(snapshot.action)) storeSticky(snapshot)
            val targets = buildList {
                addAll(staticTargets(snapshot))
                addAll(dynamicTargets(snapshot))
            }
            if (targets.isEmpty()) return@post
            dispatch(
                targets = targets,
                intent = snapshot,
                ordered = false,
                resultReceiver = null,
                scheduler = null,
                state = ResultState(0, null, null)
            )
        }
    }

    private fun isStickyAction(action: String?): Boolean =
        action == Intent.ACTION_BATTERY_CHANGED ||
            action == Intent.ACTION_DOCK_EVENT

    fun register(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        permission: String?,
        scheduler: Handler?,
        flags: Int
    ): Intent? {
        val safeFilter = filter ?: return null
        val sticky = findSticky(safeFilter)

        // Context.registerReceiver(null, filter) is the documented way to read
        // a sticky broadcast; it must not create a registration.
        if (receiver == null) return sticky

        val record = DynamicRecord(
            receiver = receiver,
            filter = IntentFilter(safeFilter),
            permission = permission,
            scheduler = scheduler,
            flags = flags
        )
        synchronized(dynamicRecords) { dynamicRecords.add(record) }
        ensureBridgedActions(
            buildList {
                val iterator = safeFilter.actionsIterator()
                while (iterator.hasNext()) add(iterator.next())
            }
        )
        Log.d(
            TAG,
            "register ${receiver.javaClass.name} actions=${safeFilter.countActions()} " +
                "permission=$permission flags=$flags"
        )

        if (sticky != null) {
            mainHandler.post {
                if (isRegistered(record)) {
                    deliverDynamic(record, sticky, ordered = false, sticky = true, state = null)
                }
            }
        }
        return sticky
    }

    fun unregister(receiver: BroadcastReceiver): Boolean {
        val removed = synchronized(dynamicRecords) {
            dynamicRecords.removeAll { it.receiver === receiver }
        }
        if (removed) Log.d(TAG, "unregister ${receiver.javaClass.name}")
        return removed
    }

    /**
     * Returns true when the broadcast was handled by the container (including
     * a target-scoped broadcast that had no matching receiver). False means the
     * caller should hand the intent to the host Context.
     */
    fun send(
        intent: Intent,
        receiverPermission: String?,
        ordered: Boolean,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?,
        sticky: Boolean
    ): Boolean {
        val component = intent.component
        if (component != null && component.packageName != session.spec.packageName) {
            return false
        }
        val targetPackage = intent.`package`
        if (targetPackage != null && targetPackage != session.spec.packageName) {
            return false
        }

        val targetScoped = component != null || targetPackage == session.spec.packageName
        if (receiverPermission != null) {
            Log.d(TAG, "broadcast receiverPermission=$receiverPermission (not enforced)")
        }

        val registeredOnly =
            intent.flags and Intent.FLAG_RECEIVER_REGISTERED_ONLY != 0
        val targets = buildList {
            if (!registeredOnly) addAll(staticTargets(intent))
            addAll(dynamicTargets(intent))
        }
        if (sticky) storeSticky(intent)
        if (targets.isEmpty()) {
            // A target-scoped ordered broadcast with no matching receiver still
            // invokes its result receiver with the initial result.
            if (targetScoped && ordered && resultReceiver != null) {
                val snapshot = Intent(intent)
                val state = ResultState(initialCode, initialData, initialExtras)
                mainHandler.post {
                    invokeReceiver(resultReceiver, snapshot, ordered = true, sticky = false, state = state)
                }
            }
            return targetScoped
        }

        val snapshot = Intent(intent)
        mainHandler.post {
            dispatch(
                targets = targets,
                intent = snapshot,
                ordered = ordered,
                resultReceiver = resultReceiver,
                scheduler = scheduler,
                state = ResultState(initialCode, initialData, initialExtras)
            )
        }
        // Non-target-scoped broadcasts also go to the host/system so external
        // receivers (and system services) can see them. Ordered broadcasts
        // with local receivers stay local to avoid delivering the final result
        // twice.
        return targetScoped || (ordered && targets.isNotEmpty())
    }

    fun destroy() {
        hostReceiver?.let { runCatching { hostContext.unregisterReceiver(it) } }
        hostReceiver = null
        synchronized(bridgedActions) { bridgedActions.clear() }
        synchronized(dynamicRecords) { dynamicRecords.clear() }
        synchronized(stickyIntents) { stickyIntents.clear() }
    }

    private fun staticTargets(intent: Intent): List<Target> =
        session.intents.queryReceivers(intent, 0)
            .filter { it.info.enabled }
            .groupBy { it.info.name }
            .mapNotNull { (_, matches) ->
                val best = matches.maxByOrNull { it.filter?.priority ?: 0 }
                    ?: return@mapNotNull null
                Target.Static(best.info, best.filter?.priority ?: 0)
            }

    private fun dynamicTargets(intent: Intent): List<Target> {
        // Explicit broadcasts resolve only to manifest components.
        if (intent.component != null) return emptyList()
        val resolver = session.context.targetContentResolver
            ?: session.hostContext.contentResolver
        val snapshot = synchronized(dynamicRecords) { dynamicRecords.toList() }
        val bestByReceiver = linkedMapOf<BroadcastReceiver, DynamicRecord>()
        snapshot.forEach { record ->
            val match = runCatching {
                record.filter.match(resolver, intent, true, TAG)
            }.getOrDefault(-1)
            if (match >= 0) {
                val previous = bestByReceiver[record.receiver]
                if (previous == null || record.filter.priority > previous.filter.priority) {
                    bestByReceiver[record.receiver] = record
                }
            }
        }
        return bestByReceiver.values.map {
            Target.Dynamic(it, it.filter.priority)
        }
    }

    private fun dispatch(
        targets: List<Target>,
        intent: Intent,
        ordered: Boolean,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        state: ResultState
    ) {
        val sorted = targets.sortedByDescending { it.priority }
        for (target in sorted) {
            if (state.aborted) break
            when (target) {
                is Target.Static -> deliverStatic(target, intent, ordered, state)
                is Target.Dynamic -> {
                    if (isRegistered(target.record)) {
                        deliverDynamic(target.record, intent, ordered, false, state)
                    }
                }
            }
        }

        if (resultReceiver != null && !state.aborted) {
            val target = scheduler ?: mainHandler
            target.post {
                invokeReceiver(resultReceiver, intent, ordered = true, sticky = false, state = state)
            }
        }
    }

    private fun deliverStatic(
        target: Target.Static,
        intent: Intent,
        ordered: Boolean,
        state: ResultState
    ) {
        val receiver = try {
            session.loader.loadClass(target.info.name)
                .asSubclass(BroadcastReceiver::class.java)
                .getDeclaredConstructor()
                .newInstance()
        } catch (t: Throwable) {
            Log.w(TAG, "create receiver failed: ${target.info.name}", t)
            return
        }
        invokeReceiver(receiver, intent, ordered, sticky = false, state = state)
    }

    private fun deliverDynamic(
        record: DynamicRecord,
        intent: Intent,
        ordered: Boolean,
        sticky: Boolean,
        state: ResultState?
    ) {
        val scheduler = record.scheduler
        if (!ordered && scheduler != null) {
            scheduler.post {
                if (isRegistered(record)) {
                    invokeReceiver(record.receiver, intent, ordered, sticky, state)
                }
            }
        } else {
            invokeReceiver(record.receiver, intent, ordered, sticky, state)
        }
    }

    /**
     * Calls onReceive with a synthetic PendingResult so getResultCode/setResult/
     * goAsync don't NPE. PendingResult's token is inert; goAsync().finish() is a
     * no-op because the result is marked as assumeDelivered.
     */
    private fun invokeReceiver(
        receiver: BroadcastReceiver,
        intent: Intent,
        ordered: Boolean,
        sticky: Boolean,
        state: ResultState?
    ): Boolean {
        val pending = createPendingResult(
            code = state?.code ?: 0,
            data = state?.data,
            extras = state?.extras,
            ordered = ordered,
            sticky = sticky
        )
        if (pending != null) {
            runCatching { setPendingResult.invoke(receiver, pending) }
                .onFailure { Log.w(TAG, "setPendingResult failed", it) }
        }
        try {
            receiver.onReceive(session.context, Intent(intent))
        } catch (t: Throwable) {
            Log.w(TAG, "onReceive failed: ${receiver.javaClass.name}", t)
        } finally {
            if (pending != null && state != null) {
                state.code = runCatching { receiver.resultCode }.getOrDefault(state.code)
                state.data = runCatching { receiver.resultData }.getOrDefault(state.data)
                state.extras = runCatching { receiver.getResultExtras(false) }
                    .getOrDefault(state.extras)
                state.aborted = runCatching { receiver.abortBroadcast }
                    .getOrDefault(false)
            }
            runCatching { setPendingResult.invoke(receiver, null as Any?) }
        }
        return state?.aborted ?: false
    }

    private fun isRegistered(record: DynamicRecord): Boolean =
        synchronized(dynamicRecords) { dynamicRecords.any { it === record } }

    private fun storeSticky(intent: Intent) {
        synchronized(stickyIntents) {
            stickyIntents.removeAll {
                it.action == intent.action &&
                    it.type == intent.type &&
                    it.data == intent.data
            }
            stickyIntents.add(Intent(intent))
        }
    }

    private fun findSticky(filter: IntentFilter): Intent? {
        val resolver = session.context.targetContentResolver
            ?: session.hostContext.contentResolver
        val match = synchronized(stickyIntents) {
            stickyIntents.asReversed().firstOrNull {
                runCatching { filter.match(resolver, it, true, TAG) }
                    .getOrDefault(-1) >= 0
            }
        }
        if (match != null) return Intent(match)

        // System sticky broadcasts (battery, dock, ...) live in the host's
        // receiver registry, not in the container.
        return runCatching { hostContext.registerReceiver(null, filter) }
            .getOrNull()
            ?.let { Intent(it).also { copied -> storeSticky(copied) } }
    }

    private fun createPendingResult(
        code: Int,
        data: String?,
        extras: Bundle?,
        ordered: Boolean,
        sticky: Boolean
    ): BroadcastReceiver.PendingResult? {
        val constructors = pendingResultConstructors
        if (constructors.isEmpty()) return null

        for (constructor in constructors) {
            val args = constructor.parameterTypes.mapIndexed { index, type ->
                when {
                    index == 0 -> code
                    index == 1 -> data
                    index == 2 -> extras
                    index == 3 -> 1 // TYPE_REGISTERED
                    index == 4 -> ordered
                    index == 5 -> sticky
                    type == java.lang.Boolean.TYPE -> true // assumeDelivered
                    type == IBinder::class.java -> null
                    type == java.lang.Integer.TYPE -> 0
                    type == String::class.java -> session.spec.packageName
                    else -> null
                }
            }.toTypedArray()

            val result = runCatching {
                @Suppress("UNCHECKED_CAST")
                constructor.newInstance(*args) as? BroadcastReceiver.PendingResult
            }.onFailure {
                Log.w(TAG, "create PendingResult failed (${constructor.parameterCount} args)", it)
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    companion object {
        private const val TAG = "ContainerBroadcast"
        private const val HOST_BRIDGE_PERMISSION =
            "com.coke.nestspace.permission.CONTAINER_BROADCAST"

        /** Marks host echoes of broadcasts already delivered in the container. */
        const val EXTRA_BRIDGE_ORIGIN = "nestspace.bridge.origin"

        val pendingResultConstructors: List<java.lang.reflect.Constructor<*>> by lazy {
            runCatching {
                BroadcastReceiver.PendingResult::class.java
                    .declaredConstructors
                    .filter { constructor ->
                        val types = constructor.parameterTypes
                        types.size in 8..12 &&
                            types[0] == java.lang.Integer.TYPE &&
                            types[1] == String::class.java &&
                            types[2] == Bundle::class.java &&
                            types[3] == java.lang.Integer.TYPE &&
                            types[4] == java.lang.Boolean.TYPE &&
                            types[5] == java.lang.Boolean.TYPE
                    }
                    .sortedByDescending { it.parameterCount }
                    .onEach { it.isAccessible = true }
            }.onFailure { Log.w(TAG, "PendingResult constructor lookup failed", it) }
                .getOrDefault(emptyList())
        }

        val setPendingResult: java.lang.reflect.Method by lazy {
            BroadcastReceiver::class.java
                .getDeclaredMethod(
                    "setPendingResult",
                    BroadcastReceiver.PendingResult::class.java
                )
                .apply { isAccessible = true }
        }
    }
}

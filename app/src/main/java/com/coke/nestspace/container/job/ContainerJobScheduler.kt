package com.coke.nestspace.container.job

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.PersistableBundle
import android.util.Log
import com.coke.nestspace.container.ContainerSession
import com.coke.nestspace.container.HiddenApiCompat
import java.lang.reflect.Field

/**
 * JobScheduler virtualization for one container process.
 *
 * Target JobServices are not installed for the host UID, so scheduling them
 * through the system would fail (or wake a process that has no idea how to run
 * the job). Instead we keep the job queue in the session, instantiate the
 * target JobService with the target ClassLoader and drive
 * onCreate/onStartJob/onStopJob directly. Latency, periodic intervals and the
 * common constraints (network, charging, battery-not-low, idle) are honored on
 * a best-effort basis; constraint checks that cannot be evaluated are treated
 * as satisfied. Jobs live as long as the container process, which matches the
 * "degrade gracefully" Stage D acceptance for background work.
 */
class ContainerJobScheduler(
    private val session: ContainerSession
) : JobScheduler() {

    private class JobRecord(
        val info: JobInfo,
        val serviceInfo: ServiceInfo
    ) {
        var runnable: Runnable? = null
        var params: JobParameters? = null
        var running = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val jobs = LinkedHashMap<Int, JobRecord>()
    private val services = LinkedHashMap<String, JobService>()

    override fun schedule(job: JobInfo): Int {
        val component = job.service
        if (component == null) {
            Log.w(TAG, "schedule without a service: ${job.id}")
            return RESULT_FAILURE
        }
        if (component.packageName != session.spec.packageName) {
            Log.w(TAG, "schedule for external service ignored: $component")
            return RESULT_FAILURE
        }
        val info = session.intents.findService(component.className)
        if (info == null) {
            Log.w(TAG, "JobService not declared: ${component.className}")
            return RESULT_FAILURE
        }
        val record = JobRecord(job, info)
        synchronized(jobs) {
            jobs.remove(job.id)?.let { cancelRecord(it, stop = true) }
            jobs[job.id] = record
        }
        Log.d(
            TAG,
            "schedule id=${job.id} service=${info.name} " +
                "latency=${job.minLatencyMillis} periodic=${job.isPeriodic}"
        )
        scheduleRun(record, 0)
        return RESULT_SUCCESS
    }

    override fun enqueue(job: JobInfo, work: JobWorkItem): Int =
        schedule(job)

    override fun cancel(jobId: Int) {
        val record = synchronized(jobs) { jobs.remove(jobId) } ?: return
        Log.d(TAG, "cancel $jobId")
        cancelRecord(record, stop = true)
    }

    override fun cancelAll() {
        val records = synchronized(jobs) {
            jobs.values.toList().also { jobs.clear() }
        }
        records.forEach { cancelRecord(it, stop = true) }
    }

    override fun getAllPendingJobs(): MutableList<JobInfo> =
        synchronized(jobs) { jobs.values.map { it.info }.toMutableList() }

    override fun getPendingJob(jobId: Int): JobInfo? =
        synchronized(jobs) { jobs[jobId]?.info }

    override fun forNamespace(namespace: String): JobScheduler = this

    override fun getNamespace(): String? = null

    override fun cancelInAllNamespaces() = cancelAll()

    override fun getPendingJobsInAllNamespaces(): Map<String, List<JobInfo>> =
        mapOf(DEFAULT_NAMESPACE to getAllPendingJobs())

    override fun canRunUserInitiatedJobs(): Boolean = true

    override fun getPendingJobReason(jobId: Int): Int {
        synchronized(jobs) {
            if (jobs.containsKey(jobId)) return PENDING_JOB_REASON_APP
        }
        return PENDING_JOB_REASON_UNDEFINED
    }

    override fun getPendingJobReasons(jobId: Int): IntArray {
        synchronized(jobs) {
            if (jobs.containsKey(jobId)) return intArrayOf(PENDING_JOB_REASON_APP)
        }
        return intArrayOf()
    }

    fun shutdown() {
        val records = synchronized(jobs) {
            jobs.values.toList().also { jobs.clear() }
        }
        records.forEach { cancelRecord(it, stop = true) }
        synchronized(services) {
            services.values.forEach { runCatching { it.onDestroy() } }
            services.clear()
        }
        handler.removeCallbacksAndMessages(null)
    }

    private fun scheduleRun(record: JobRecord, extraDelay: Long) {
        record.runnable?.let { handler.removeCallbacks(it) }
        if (!constraintsSatisfied(record.info)) {
            record.runnable = Runnable { scheduleRun(record, 0) }.also {
                handler.postDelayed(it, CONSTRAINT_RETRY_MS)
            }
            return
        }
        val latency = maxOf(0L, record.info.minLatencyMillis)
        val delay = if (extraDelay > 0) extraDelay else latency
        record.runnable = Runnable { runJob(record) }.also {
            handler.postDelayed(it, delay)
        }
    }

    private fun runJob(record: JobRecord) {
        synchronized(jobs) {
            if (jobs[record.info.id] !== record) return
        }
        if (record.running) return
        val service = obtainService(record.serviceInfo) ?: return
        record.running = true
        val start = Runnable {
            val params = createParams(record) ?: run {
                record.running = false
                return@Runnable
            }
            record.params = params
            val started = runCatching { service.onStartJob(params) }
                .onFailure { Log.w(TAG, "onStartJob failed: ${record.serviceInfo.name}", it) }
                .getOrDefault(false)
            Log.d(TAG, "onStartJob ${record.info.id} started=$started")
            if (!started) finishRecord(record, wantsReschedule = record.info.isPeriodic)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            start.run()
        } else {
            handler.post(start)
        }
    }

    private fun finishRecord(record: JobRecord, wantsReschedule: Boolean) {
        record.running = false
        record.params = null
        val stillScheduled = synchronized(jobs) { jobs[record.info.id] === record }
        if (!stillScheduled) return
        when {
            record.info.isPeriodic -> {
                val interval = maxOf(record.info.intervalMillis, 1_000L)
                scheduleRun(record, interval)
            }
            wantsReschedule -> scheduleRun(
                record,
                maxOf(record.info.initialBackoffMillis, 10_000L)
            )
            else -> synchronized(jobs) {
                if (jobs[record.info.id] === record) jobs.remove(record.info.id)
            }
        }
    }

    private fun cancelRecord(record: JobRecord, stop: Boolean) {
        record.runnable?.let { handler.removeCallbacks(it) }
        record.runnable = null
        if (stop) {
            val params = record.params
            val service = synchronized(services) { services[record.serviceInfo.name] }
            if (service != null) {
                runCatching { service.onStopJob(params ?: createParams(record)) }
                    .onFailure { Log.w(TAG, "onStopJob failed: ${record.serviceInfo.name}", it) }
            }
        }
        record.running = false
        record.params = null
    }

    private fun obtainService(info: ServiceInfo): JobService? {
        synchronized(services) {
            services[info.name]?.let { return it }
            return try {
                val cls = session.loader.loadClass(info.name).asSubclass(JobService::class.java)
                val service = cls.getDeclaredConstructor().newInstance()
                attachBaseContext(service)
                setField(service, listOf("mApplication"), session.application)
                service.onCreate()
                // Initializes the framework's JobServiceEngine so target code
                // can call jobFinished()/setNotification() without an NPE.
                runCatching { service.onBind(Intent()) }
                    .onFailure { Log.w(TAG, "initialize JobService engine failed", it) }
                services[info.name] = service
                Log.d(TAG, "JobService created: ${info.name}")
                service
            } catch (t: Throwable) {
                Log.w(TAG, "create JobService failed: ${info.name}", t)
                null
            }
        }
    }

    private fun attachBaseContext(service: JobService) {
        var cls: Class<*>? = service.javaClass
        while (cls != null) {
            val method = runCatching {
                cls.getDeclaredMethod("attachBaseContext", Context::class.java)
            }.getOrNull()
            if (method != null) {
                method.isAccessible = true
                method.invoke(service, session.context)
                return
            }
            cls = cls.superclass
        }
        Log.w(TAG, "JobService.attachBaseContext not found: ${service.javaClass.name}")
    }

    private fun createParams(record: JobRecord): JobParameters? {
        HiddenApiCompat.ensure()
        val job = record.info
        val constructor = JobParameters::class.java.declaredConstructors
            .filter { it.parameterTypes.size >= 13 }
            .minByOrNull { it.parameterTypes.size }
            ?: return null
        return runCatching {
            constructor.isAccessible = true
            val args = constructor.parameterTypes.mapIndexed { index, type ->
                when (index) {
                    // Without a callback the framework drops jobFinished()
                    // (and NPEs inside JobService.jobFinished). Pass a binder
                    // that feeds completion back into the virtual scheduler.
                    0 -> JobFinishedCallback(this, record)
                    1 -> null // namespace
                    2 -> job.id
                    3 -> job.extras ?: PersistableBundle()
                    4 -> runCatching { job.transientExtras }.getOrNull() ?: Bundle()
                    5 -> runCatching { job.clipData }.getOrNull()
                    6 -> runCatching { job.clipGrantFlags }.getOrDefault(0)
                    7 -> false // overrideDeadlineExpired
                    8 -> runCatching { job.isExpedited }.getOrDefault(false)
                    9 -> runCatching { job.isUserInitiated }.getOrDefault(false)
                    10 -> runCatching { job.triggerContentUris }.getOrNull()
                    11 -> null // triggeredContentAuthorities
                    12 -> runCatching { job.requiredNetwork }.getOrNull()
                    else -> defaultValue(type)
                }
            }.toTypedArray()
            constructor.newInstance(*args) as JobParameters
        }.onFailure {
            Log.w(TAG, "create JobParameters failed", it)
        }.getOrNull()
    }

    /**
     * Binder backing [JobParameters.getCallback]. JobService.jobFinished()
     * routes through the framework engine's handler and then back here, so
     * the scheduler learns that an asynchronous job completed.
     */
    private class JobFinishedCallback(
        private val scheduler: ContainerJobScheduler,
        private val record: JobRecord
    ) : Binder() {

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                TRANSACTION_JOB_FINISHED -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    val jobId = data.readInt()
                    val wantsReschedule = data.readInt() != 0
                    if (jobId == record.info.id) {
                        scheduler.handler.post {
                            scheduler.finishRecord(
                                record,
                                wantsReschedule || record.info.isPeriodic
                            )
                        }
                    }
                    reply?.writeNoException()
                    true
                }
                TRANSACTION_DEQUEUE_WORK -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    data.readInt()
                    reply?.writeNoException()
                    reply?.writeInt(0) // no queued work items in this container
                    true
                }
                TRANSACTION_COMPLETE_WORK -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    data.readInt()
                    data.readInt()
                    reply?.writeNoException()
                    reply?.writeInt(0) // not an active work item
                    true
                }
                TRANSACTION_HANDLE_ABANDONED -> {
                    reply?.writeNoException()
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    private fun constraintsSatisfied(job: JobInfo): Boolean {
        return runCatching {
            if (job.networkType != JobInfo.NETWORK_TYPE_NONE) {
                val connectivity = session.hostContext.getSystemService(
                    android.net.ConnectivityManager::class.java
                )
                if (connectivity?.activeNetwork == null) return false
            }
            if (job.isRequireCharging) {
                val battery = session.hostContext.getSystemService(BatteryManager::class.java)
                if (battery?.isCharging != true) return false
            }
            if (job.isRequireBatteryNotLow) {
                val battery = session.hostContext.getSystemService(BatteryManager::class.java)
                val charging = battery?.isCharging == true
                val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    ?: 100
                if (!charging && level in 0..15) return false
            }
            if (job.isRequireDeviceIdle) {
                val power = session.hostContext.getSystemService(
                    android.os.PowerManager::class.java
                )
                if (power?.isDeviceIdleMode != true) return false
            }
            true
        }.getOrDefault(true)
    }

    private fun setField(target: Any, names: List<String>, value: Any?) {
        val field = findField(target.javaClass, names) ?: return
        runCatching {
            field.isAccessible = true
            field.set(target, value)
        }.onFailure { Log.w(TAG, "set ${names.first()} failed", it) }
    }

    private fun findField(type: Class<*>, names: List<String>): Field? {
        var current: Class<*>? = type
        while (current != null) {
            names.forEach { name ->
                runCatching { current!!.getDeclaredField(name) }.getOrNull()?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private companion object {
        const val TAG = "ContainerJobScheduler"
        const val CONSTRAINT_RETRY_MS = 15_000L
        const val DEFAULT_NAMESPACE = ""

        const val CALLBACK_DESCRIPTOR = "android.app.job.IJobCallback"
        val TRANSACTION_JOB_FINISHED: Int = transactionCode("jobFinished", 1)
        val TRANSACTION_DEQUEUE_WORK: Int = transactionCode("dequeueWork", 2)
        val TRANSACTION_COMPLETE_WORK: Int = transactionCode("completeWork", 3)
        val TRANSACTION_HANDLE_ABANDONED: Int = transactionCode("handleAbandonedJob", 4)

        fun transactionCode(name: String, fallback: Int): Int = runCatching {
            val stub = Class.forName("android.app.job.IJobCallback\$Stub")
            val field = runCatching { stub.getField("TRANSACTION_$name") }.getOrNull()
                ?: stub.getDeclaredField("TRANSACTION_$name").apply { isAccessible = true }
            field.getInt(null)
        }.getOrDefault(fallback)
    }
}

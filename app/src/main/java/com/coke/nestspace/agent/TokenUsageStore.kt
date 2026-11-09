package com.coke.nestspace.agent

import android.content.Context
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.llm.ChatUsage
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class TokenUsageTotals(
    val requests: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val estimatedRequests: Long = 0
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

data class TokenUsageModel(
    val id: String,
    val name: String,
    val model: String,
    val totals: TokenUsageTotals
)

data class TokenUsageEntry(
    val at: Long,
    val configId: String,
    val name: String,
    val model: String,
    val kind: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val cachedInputTokens: Long,
    val estimated: Boolean
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

data class TokenUsageSnapshot(
    val total: TokenUsageTotals,
    val today: TokenUsageTotals,
    val models: List<TokenUsageModel>,
    val recent: List<TokenUsageEntry>,
    /** Daily totals keyed by yyyy-MM-dd, for the calendar view. */
    val days: Map<String, TokenUsageTotals>
)

/**
 * Persistent per-request model token accounting.
 *
 * Every completed ChatService request records one entry, including retries,
 * final summaries and connectivity probes. Values come from the provider's
 * usage object; endpoints that omit it are estimated from text length and
 * flagged so the UI does not present estimates as billing data.
 */
class TokenUsageStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val writeLock = Any()
    private var cached: JSONObject? = null
    private var revision = 0L
    private var pendingSave: ScheduledFuture<*>? = null
    private val writer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "token-usage-writer").apply { isDaemon = true }
    }

    fun record(config: LlmConfig, usage: ChatUsage, kind: String = "chat") {
        synchronized(lock) {
            val data = load()
            val now = System.currentTimeMillis()
            val today = dayKey(now)
            if (data.optString("today_date") != today) {
                data.put("today_date", today)
                data.put("today", JSONObject())
            }
            increment(
                data.optJSONObject("total")
                    ?: JSONObject().also { data.put("total", it) },
                usage
            )
            val todayTotals = data.getJSONObject("today")
            val days = data.optJSONObject("days")
                ?: JSONObject().also { data.put("days", it) }
            val dayTotals = days.optJSONObject(today) ?: JSONObject().also {
                // First daily record after an upgrade: carry today's existing
                // aggregate over so the calendar does not under-report it.
                copyTotals(todayTotals, it)
                days.put(today, it)
            }
            increment(todayTotals, usage)
            increment(dayTotals, usage)
            if (days.length() > DAY_LIMIT) {
                val oldest = days.keys().asSequence().sorted().take(days.length() - DAY_LIMIT)
                oldest.forEach { days.remove(it) }
            }

            val key = config.id.ifBlank { "${config.model}|${config.name}" }
            val models = data.optJSONObject("models")
                ?: JSONObject().also { data.put("models", it) }
            val model = models.optJSONObject(key)
                ?: JSONObject().also { models.put(key, it) }
            model.put("name", config.name)
            model.put("model", config.model)
            increment(
                model.optJSONObject("totals")
                    ?: JSONObject().also { model.put("totals", it) },
                usage
            )

            val recent = data.optJSONArray("recent")
                ?: JSONArray().also { data.put("recent", it) }
            recent.put(
                JSONObject()
                    .put("at", now)
                    .put("config", key)
                    .put("name", config.name)
                    .put("model", config.model)
                    .put("kind", kind)
                    .put("input", usage.inputTokens)
                    .put("output", usage.outputTokens)
                    .put("reasoning", usage.reasoningTokens)
                    .put("cached", usage.cachedInputTokens)
                    .put("estimated", usage.estimated)
            )
            while (recent.length() > RECENT_LIMIT) recent.remove(0)
            scheduleSaveLocked()
        }
    }

    fun snapshot(): TokenUsageSnapshot = synchronized(lock) {
        val data = load()
        val todayKey = dayKey(System.currentTimeMillis())
        val today = if (data.optString("today_date") == todayKey) {
            totals(data.optJSONObject("today"))
        } else {
            TokenUsageTotals()
        }
        val models = data.optJSONObject("models")?.let { json ->
            json.keys().asSequence().mapNotNull { key ->
                val item = json.optJSONObject(key) ?: return@mapNotNull null
                TokenUsageModel(
                    id = key,
                    name = item.optString("name"),
                    model = item.optString("model"),
                    totals = totals(item.optJSONObject("totals"))
                )
            }.sortedByDescending { it.totals.totalTokens }.toList()
        }.orEmpty()
        val recent = data.optJSONArray("recent")?.let { array ->
            buildList {
                for (i in array.length() - 1 downTo 0) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        TokenUsageEntry(
                            at = item.optLong("at"),
                            configId = item.optString("config"),
                            name = item.optString("name"),
                            model = item.optString("model"),
                            kind = item.optString("kind"),
                            inputTokens = item.optLong("input"),
                            outputTokens = item.optLong("output"),
                            reasoningTokens = item.optLong("reasoning"),
                            cachedInputTokens = item.optLong("cached"),
                            estimated = item.optBoolean("estimated")
                        )
                    )
                }
            }
        }.orEmpty()
        val days = sortedMapOf<String, TokenUsageTotals>()
        data.optJSONObject("days")?.keys()?.forEach { key ->
            days[key] = totals(data.getJSONObject("days").optJSONObject(key))
        }
        // Older records only had a "today" bucket; fold it into the calendar
        // so upgrading does not hide the current day's usage.
        val todayDate = data.optString("today_date")
        if (todayDate.isNotBlank() && days[todayDate] == null &&
            data.optJSONObject("today") != null
        ) {
            days[todayDate] = totals(data.optJSONObject("today"))
        }
        TokenUsageSnapshot(
            total = totals(data.optJSONObject("total")),
            today = today,
            models = models,
            recent = recent,
            days = days
        )
    }

    fun reset() {
        synchronized(lock) {
            revision++
            pendingSave?.cancel(false)
            pendingSave = null
            cached = JSONObject()
        }
        synchronized(writeLock) {
            prefs.edit().remove(KEY).commit()
        }
    }

    private fun load(): JSONObject {
        cached?.let { return it }
        val data = runCatching {
            prefs.getString(KEY, null)?.let { JSONObject(it) }
        }.getOrNull() ?: JSONObject()
        cached = data
        return data
    }

    private fun scheduleSaveLocked() {
        revision++
        val expectedRevision = revision
        pendingSave?.cancel(false)
        pendingSave = writer.schedule({
            synchronized(writeLock) {
                val serialized = synchronized(lock) {
                    if (revision != expectedRevision) {
                        null
                    } else {
                        pendingSave = null
                        cached?.toString()
                    }
                }
                if (serialized != null) {
                    prefs.edit().putString(KEY, serialized).commit()
                }
            }
        }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun increment(totals: JSONObject, usage: ChatUsage) {
        totals.put("requests", totals.optLong("requests") + 1)
        totals.put("input", totals.optLong("input") + usage.inputTokens)
        totals.put("output", totals.optLong("output") + usage.outputTokens)
        totals.put("reasoning", totals.optLong("reasoning") + usage.reasoningTokens)
        totals.put("cached", totals.optLong("cached") + usage.cachedInputTokens)
        if (usage.estimated) {
            totals.put("estimated", totals.optLong("estimated") + 1)
        }
    }

    private fun copyTotals(from: JSONObject, to: JSONObject) {
        listOf("requests", "input", "output", "reasoning", "cached", "estimated")
            .forEach { key -> to.put(key, from.optLong(key)) }
    }

    private fun totals(json: JSONObject?): TokenUsageTotals {
        if (json == null) return TokenUsageTotals()
        return TokenUsageTotals(
            requests = json.optLong("requests"),
            inputTokens = json.optLong("input"),
            outputTokens = json.optLong("output"),
            reasoningTokens = json.optLong("reasoning"),
            cachedInputTokens = json.optLong("cached"),
            estimatedRequests = json.optLong("estimated")
        )
    }

    companion object {
        private const val PREFS = "token_usage"
        private const val KEY = "state.v1"
        private const val RECENT_LIMIT = 100
        private const val DAY_LIMIT = 400
        private const val SAVE_DELAY_MS = 500L

        @Volatile
        private var instance: TokenUsageStore? = null

        fun get(context: Context): TokenUsageStore =
            instance ?: synchronized(this) {
                instance ?: TokenUsageStore(context).also { instance = it }
            }

        fun dayKey(at: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(at))
    }
}

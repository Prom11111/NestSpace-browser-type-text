package com.coke.nestspace.memory

import android.content.Context
import com.coke.nestspace.data.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class MemoryKind(val api: String) {
    FACT("fact"),
    PREFERENCE("preference"),
    PROCEDURE("procedure");

    companion object {
        fun from(value: String?): MemoryKind =
            entries.firstOrNull { it.api == value?.lowercase() } ?: FACT
    }
}

data class MemoryItem(
    val id: String,
    val kind: MemoryKind,
    val content: String,
    val keywords: List<String>,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val approved: Boolean,
    val pinned: Boolean
)

/**
 * User-approved notes. Facts and preferences can be active immediately;
 * procedures are saved pending until the user approves them in Memory & skills.
 * Only approved notes are injected into the model context.
 */
class MemoryStore(context: Context) {

    private val appContext = context.applicationContext
    private val memoryFile = File(appContext.filesDir, FILE)
    // One process-wide lock: several MemoryStore instances (tools, agent,
    // settings UI) read-modify-write the same file and must not lose updates.
    private val lock: Any get() = LOCK

    fun list(): List<MemoryItem> = synchronized(lock) { load() }

    fun approved(): List<MemoryItem> =
        list().filter { it.approved }.sortedWith(compareByDescending<MemoryItem> { it.pinned }
            .thenByDescending { it.updatedAt })

    fun add(
        kind: MemoryKind,
        content: String,
        keywords: List<String>,
        source: String,
        approved: Boolean = false
    ): Result<MemoryItem> {
        val text = MemoryPolicy.normalize(content)
        if (text.isBlank()) return Result.failure(MemoryException("empty"))
        if (text.length > MemoryPolicy.MAX_CONTENT) {
            return Result.failure(MemoryException("too_long"))
        }
        MemoryPolicy.sensitiveReason(text)?.let {
            return Result.failure(MemoryException("sensitive"))
        }
        val normalizedKeywords = MemoryPolicy.normalizeKeywords(keywords, text)
        val normalizedSource = source.trim().take(MemoryPolicy.MAX_SOURCE)
        // Do not persist a password/code/card number through the quote or a
        // keyword even when the one-line content itself looks harmless.
        val sensitive = MemoryPolicy.sensitiveReason(normalizedSource) != null ||
            normalizedKeywords.any { MemoryPolicy.sensitiveReason(it) != null }
        if (sensitive) return Result.failure(MemoryException("sensitive"))
        val now = System.currentTimeMillis()
        val item = MemoryItem(
            id = UUID.randomUUID().toString(),
            kind = kind,
            content = text,
            keywords = normalizedKeywords,
            source = normalizedSource,
            createdAt = now,
            updatedAt = now,
            approved = approved,
            pinned = false
        )
        synchronized(lock) {
            val items = loadForWrite()
                ?: return Result.failure(MemoryException("unavailable"))
            val next = items + item
            if (!save(next)) return Result.failure(MemoryException("write_failed"))
        }
        return Result.success(item)
    }

    fun approve(id: String): Boolean = update(id) {
        it.copy(approved = true, updatedAt = System.currentTimeMillis())
    }

    fun setPinned(id: String, pinned: Boolean): Boolean = update(id) {
        it.copy(pinned = pinned, updatedAt = System.currentTimeMillis())
    }

    fun forget(id: String): Boolean = synchronized(lock) {
        val items = loadForWrite()?.toMutableList() ?: return false
        val removed = items.removeAll { it.id == id }
        if (!removed) return false
        save(items)
    }

    fun search(query: String, limit: Int = 8): List<MemoryItem> {
        val q = query.trim().lowercase()
        val all = approved()
        val cap = limit.coerceIn(1, 20)
        if (q.isBlank()) return all.take(cap)
        val ranked = MemoryRanking.search(all, q, cap)
        if (ranked.isNotEmpty()) return ranked
        // Keep exact short queries ("咖啡") working even when tokenization
        // produces no multi-character terms.
        return all.filter { item ->
            item.content.lowercase().contains(q) ||
                item.keywords.any { it.lowercase().contains(q) }
        }.take(cap)
    }

    /** Compact approved notes for injection into the system prompt. */
    fun contextFor(query: String, limit: Int = 12): String {
        val all = approved()
        if (all.isEmpty()) return ""
        // Small stores fit entirely; do not drop an old note just because a
        // dozen newer ones exist. Large stores are ranked by relevance.
        val full = render(all)
        if (full.length <= MemoryPolicy.MAX_CONTEXT_CHARS) return full
        return render(MemoryRanking.select(all, query, limit.coerceIn(1, 20)))
    }

    private fun render(items: List<MemoryItem>): String {
        val builder = StringBuilder()
        for (item in items) {
            val line = "- [${item.kind.api}]${if (item.pinned) " (pinned)" else ""} ${item.content}"
            if (builder.length + line.length + 1 > MemoryPolicy.MAX_CONTEXT_CHARS) break
            builder.appendLine(line)
        }
        return builder.toString().trim()
    }

    private fun update(id: String, transform: (MemoryItem) -> MemoryItem): Boolean =
        synchronized(lock) {
            val items = loadForWrite()?.toMutableList() ?: return false
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return false
            items[index] = transform(items[index])
            save(items)
        }

    private fun load(): List<MemoryItem> = loadForWrite() ?: emptyList()

    /**
     * Writes must not turn a temporarily unreadable file into an empty one.
     * A missing file is an empty memory; an existing file that cannot be read
     * or parsed makes the store unavailable until the next attempt.
     */
    private fun loadForWrite(): List<MemoryItem>? {
        val raw = SecureStore.read(appContext, FILE)
        if (raw == null) return if (memoryFile.isFile) null else emptyList()
        return runCatching { parse(raw) }.getOrNull()
    }

    private fun parse(raw: String): List<MemoryItem> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            MemoryItem(
                id = json.optString("id"),
                kind = MemoryKind.from(json.optString("kind")),
                content = json.optString("content"),
                keywords = json.optJSONArray("keywords")?.let { keys ->
                    (0 until keys.length()).map { keys.optString(it) }
                }.orEmpty(),
                source = json.optString("source"),
                createdAt = json.optLong("created_at"),
                updatedAt = json.optLong("updated_at"),
                approved = json.optBoolean("approved"),
                pinned = json.optBoolean("pinned")
            )
        }.filter { it.id.isNotBlank() && it.content.isNotBlank() }
    }

    private fun save(items: List<MemoryItem>): Boolean {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("kind", item.kind.api)
                    .put("content", item.content)
                    .put("keywords", JSONArray(item.keywords))
                    .put("source", item.source)
                    .put("created_at", item.createdAt)
                    .put("updated_at", item.updatedAt)
                    .put("approved", item.approved)
                    .put("pinned", item.pinned)
            )
        }
        return SecureStore.write(appContext, FILE, array.toString())
    }

    private companion object {
        val LOCK = Any()
        const val FILE = "memory.v1.enc"
    }
}

class MemoryException(val code: String) : Exception(code)

object MemoryPolicy {
    const val MAX_CONTENT = 500
    const val MAX_SOURCE = 300
    const val MAX_CONTEXT_CHARS = 4_000
    private val WHITESPACE = Regex("\\s+")
    // A bare word such as 密码 or 银行卡 is not a secret; instructions for a
    // skill often say "let the user enter the password". Require a value next
    // to the keyword (or a card/ID-shaped number) before rejecting it.
    private val SENSITIVE = listOf(
        Regex(
            "(?:支付密码|密码|口令|验证码|校验码|动态码|安全码|安全问题|" +
                "(?<![A-Za-z])(?:password|passcode|passwd|cvv|cvc|verification code|" +
                "security code|one-time code)(?![A-Za-z]))" +
                "[\\s:：=,，是为叫]{0,4}(?:(?:is|was|are)\\s+)?[A-Za-z0-9]{4,}",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "(?<![A-Za-z0-9])[A-Za-z0-9]{4,}\\s*(?:就是|是|为)" +
                "(?:我的|我|用户的)?\\s*(?:支付密码|密码|口令|验证码|校验码|动态码|安全码|安全问题|" +
                "(?<![A-Za-z])(?:password|passcode|passwd|cvv|cvc)(?![A-Za-z]))",
            RegexOption.IGNORE_CASE
        ),
        Regex("(?i)sk-[a-z0-9]{12,}"),
        Regex(
            "(?i)(?:api[_ -]?key|secret[_ -]?key|access[_ -]?token)\\s*[:：=]\\s*" +
                "[A-Za-z0-9_\\-]{8,}"
        ),
        Regex("(?<![0-9])[1-9][0-9]{16}[0-9Xx](?![0-9A-Za-z])"),
        Regex("(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![0-9])")
    )

    fun normalize(text: String): String = WHITESPACE.replace(text.trim(), " ")

    fun sensitiveReason(text: String): String? =
        if (SENSITIVE.any { it.containsMatchIn(text) }) "sensitive" else null

    /** A saved memory must quote the user, not a web page or a tool result. */
    fun quoteSupported(quote: String, utterances: List<String>): Boolean {
        val needle = normalize(quote)
            .trim('"', '\'', '“', '”', '‘', '’')
            .lowercase()
        if (needle.isBlank()) return false
        return utterances.any { normalize(it).lowercase().contains(needle) }
    }

    fun normalizeKeywords(raw: List<String>, content: String): List<String> {
        val values = raw
            .flatMap { it.split(',', '，', ';', '；', ' ') }
            .map { normalize(it).lowercase() }
            .filter { it.length in 2..40 }
            .distinct()
            .take(10)
        if (values.isNotEmpty()) return values
        return Regex("[\\p{IsHan}]{2,6}|[A-Za-z0-9_-]{2,24}")
            .findAll(content)
            .map { it.value.lowercase() }
            .distinct()
            .take(6)
            .toList()
    }
}

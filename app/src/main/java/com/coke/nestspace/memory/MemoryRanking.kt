package com.coke.nestspace.memory

/**
 * Small local relevance ranker for memory injection.
 *
 * Chinese has no spaces, so Han runs are indexed as overlapping bigrams while
 * ASCII words are kept whole. Pinned entries always lead; the rest are ordered
 * by query overlap and recency, so an old but relevant note is not dropped just
 * because newer notes exist.
 */
internal object MemoryRanking {

    private val TOKEN = Regex("[\\p{IsHan}]+|[A-Za-z0-9]+")

    private val STOP = setOf(
        "的", "了", "是", "我", "你", "他", "她", "它", "在", "有", "和", "与",
        "就", "都", "也", "不", "吗", "呢", "吧", "啊", "要", "想", "请",
        "帮我", "给我", "一下", "我的", "这个", "那个", "可以", "一个", "用户",
        "今天", "这次", "现在", "什么", "怎么", "我们", "一些", "看看"
    )

    /** Only items that match at least one query term, best first. */
    fun search(items: List<MemoryItem>, query: String, limit: Int): List<MemoryItem> {
        val terms = queryTerms(query)
        if (terms.isEmpty()) return emptyList()
        return items.asSequence()
            .map { it to score(it, terms) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryItem, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt }
            )
            .map { it.first }
            .distinctBy { it.id }
            .take(limit.coerceIn(1, 20))
            .toList()
    }

    fun select(items: List<MemoryItem>, query: String, limit: Int): List<MemoryItem> {
        val cap = limit.coerceIn(1, 20)
        val terms = queryTerms(query)
        val pinned = items.filter { it.pinned }
            .sortedByDescending { it.updatedAt }
            .take(MAX_PINNED)
        val scored = items.asSequence()
            .filterNot { it.pinned }
            .map { it to score(it, terms) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryItem, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt }
            )
            .map { it.first }
            .toList()
        val used = (pinned + scored).map { it.id }.toSet()
        val rest = items.filterNot { it.id in used }
            .sortedByDescending { it.updatedAt }
        return (pinned + scored + rest)
            .distinctBy { it.id }
            .take(cap)
    }

    internal fun queryTerms(query: String): List<String> {
        val terms = mutableListOf<String>()
        TOKEN.findAll(query.lowercase()).forEach { match ->
            val value = match.value
            if (value.first().isHan()) {
                if (value.length == 1) {
                    terms += value
                } else {
                    for (i in 0 until value.length - 1) {
                        terms += value.substring(i, i + 2)
                    }
                }
            } else {
                terms += value
            }
        }
        return terms.filter {
            (it.length >= 2 || it.first().isHan()) && it !in STOP
        }.distinct()
    }

    internal fun score(item: MemoryItem, terms: List<String>): Int {
        if (terms.isEmpty()) return 0
        val content = item.content.lowercase()
        val keywords = item.keywords.joinToString(" ").lowercase()
        var score = 0
        for (term in terms) {
            if (content.contains(term)) score += CONTENT_HIT
            if (keywords.contains(term)) score += KEYWORD_HIT
        }
        return score
    }

    private fun Char.isHan(): Boolean =
        Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN

    private const val CONTENT_HIT = 2
    private const val KEYWORD_HIT = 4
    private const val MAX_PINNED = 5
}

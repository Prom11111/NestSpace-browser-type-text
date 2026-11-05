package com.coke.nestspace.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRankingTest {

    private fun memory(
        id: String,
        content: String,
        keywords: List<String> = emptyList(),
        pinned: Boolean = false,
        updatedAt: Long = 0L
    ) = MemoryItem(
        id = id,
        kind = MemoryKind.PREFERENCE,
        content = content,
        keywords = keywords,
        source = "test",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        approved = true,
        pinned = pinned
    )

    @Test
    fun matchesChineseBigramsAndPrefersRelevantOldNotes() {
        val items = listOf(
            memory("coffee", "用户喜欢喝无糖美式咖啡", listOf("咖啡"), updatedAt = 1),
            memory("city", "用户住在杭州", listOf("杭州"), updatedAt = 100),
            memory("food", "用户不吃香菜", listOf("香菜", "忌口"), updatedAt = 200)
        )
        val selected = MemoryRanking.select(items, "推荐点咖啡", limit = 2)
        assertEquals("coffee", selected.first().id)
        assertTrue(selected.any { it.id == "coffee" })
    }

    @Test
    fun searchFindsRelevantWordsInsteadOfWholeSentence() {
        val items = listOf(
            memory("coffee", "用户喜欢喝无糖美式咖啡", listOf("咖啡")),
            memory("city", "用户住在杭州", listOf("杭州"))
        )
        assertEquals("coffee", MemoryRanking.search(items, "我想喝咖啡", 8).first().id)
        assertTrue(MemoryRanking.search(items, "天气怎么样", 8).isEmpty())
    }

    @Test
    fun pinnedAlwaysLeads() {
        val items = listOf(
            memory("old-pinned", "用户不吃香菜", pinned = true, updatedAt = 1),
            memory("new", "用户住在杭州", updatedAt = 100)
        )
        assertEquals("old-pinned", MemoryRanking.select(items, "杭州", 1).first().id)
    }

    @Test
    fun quoteMustComeFromUserWords() {
        val turns = listOf("请记住：我喜欢喝无糖美式咖啡。")
        assertTrue(MemoryPolicy.quoteSupported("我喜欢喝无糖美式咖啡", turns))
        assertTrue(MemoryPolicy.quoteSupported("“我喜欢喝无糖美式咖啡。”", turns))
        assertFalse(MemoryPolicy.quoteSupported("网页上说用户喜欢咖啡", turns))
        assertFalse(MemoryPolicy.quoteSupported("", turns))
    }
}

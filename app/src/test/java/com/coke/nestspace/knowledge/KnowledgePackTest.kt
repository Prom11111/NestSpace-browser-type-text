package com.coke.nestspace.knowledge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KnowledgePackTest {

    private val root: File = listOf(
        File("src/main/assets/knowledge"),
        File("app/src/main/assets/knowledge")
    ).firstOrNull { it.isDirectory }
        ?: error("knowledge assets not found from ${File(".").absolutePath}")

    @Test
    fun appGuidesParse() {
        val entries = read(KnowledgeEntry.Kind.APP, "apps", "APP.md")
        assertTrue("expected app guides", entries.isNotEmpty())
        for (entry in entries) {
            assertTrue("blank display in ${entry.name}", entry.display.isNotBlank())
            assertTrue("no package in ${entry.name}", entry.packages.isNotEmpty())
            assertTrue("body too long in ${entry.name}", entry.body.length <= 4_000)
        }
    }

    @Test
    fun siteGuidesParse() {
        val entries = read(KnowledgeEntry.Kind.SITE, "sites", "SITE.md")
        assertTrue("expected site guides", entries.isNotEmpty())
        for (entry in entries) {
            assertTrue("blank display in ${entry.name}", entry.display.isNotBlank())
            assertTrue("no domain in ${entry.name}", entry.domains.isNotEmpty())
            assertTrue("directory name missing from domains: ${entry.name}", entry.name in entry.domains)
            assertTrue("body too long in ${entry.name}", entry.body.length <= 4_000)
        }
    }

    @Test
    fun packagesAndDomainsAreUnique() {
        val apps = read(KnowledgeEntry.Kind.APP, "apps", "APP.md")
        val sites = read(KnowledgeEntry.Kind.SITE, "sites", "SITE.md")
        val packages = apps.flatMap { it.packages }
        assertEquals("duplicate package", packages.size, packages.distinct().size)
        val domains = sites.flatMap { it.domains }
        assertEquals("duplicate domain", domains.size, domains.distinct().size)
    }

    @Test
    fun guidesDoNotContainLocalPaths() {
        val all = read(KnowledgeEntry.Kind.APP, "apps", "APP.md") +
            read(KnowledgeEntry.Kind.SITE, "sites", "SITE.md")
        for (entry in all) {
            assertTrue("local path in ${entry.name}", !entry.body.contains("/Users/"))
        }
    }

    @Test
    fun matchesByPackageAndMostSpecificHost() {
        val store = KnowledgeStore.of(
            read(KnowledgeEntry.Kind.APP, "apps", "APP.md") +
                read(KnowledgeEntry.Kind.SITE, "sites", "SITE.md")
        )

        assertNotNull(store.forPackage("tv.danmaku.bili"))
        assertNotNull(store.forPackage("com.xunmeng.pinduoduo"))
        assertNotNull(store.forPackage("com.eg.android.AlipayGphone"))
        assertNotNull(store.forPackage("com.deepseek.chat"))
        assertNotNull(store.forPackage("com.ss.android.article.news"))
        assertNull(store.forPackage("com.example.unknown"))

        assertEquals("bilibili.com", store.forHost("www.bilibili.com")?.name)
        assertEquals("bilibili.com", store.forHost("search.bilibili.com")?.name)
        assertEquals("zhihu.com", store.forHost("www.zhihu.com")?.name)
        assertEquals("12306.cn", store.forHost("www.12306.cn")?.name)
        assertEquals("qq.com", store.forHost("xw.qq.com")?.name)
        assertEquals("ele.me", store.forHost("www.ele.me")?.name)
        assertNull(store.forHost("example.com"))
    }

    @Test
    fun matchesToolCallsAndInjectsGuideOnce() {
        val store = KnowledgeStore.of(
            read(KnowledgeEntry.Kind.APP, "apps", "APP.md") +
                read(KnowledgeEntry.Kind.SITE, "sites", "SITE.md")
        )

        val app = store.forTool(
            "space_launch",
            JSONObject().put("package", "com.xunmeng.pinduoduo"),
            "{}"
        )
        assertNotNull(app)

        val site = store.forTool(
            "browser_open",
            JSONObject().put("url", "https://www.bilibili.com/video/BV1"),
            JSONObject().put("url", "https://www.bilibili.com/video/BV1").toString()
        )
        assertEquals("bilibili.com", site?.name)

        val withGuide = JSONObject(site!!.withGuide("{\"opened\":true}"))
        assertTrue(withGuide.getBoolean("opened"))
        assertTrue(withGuide.getString("guide").contains("NestSpace guide"))
        assertTrue(withGuide.getString("guide").contains("哔哩哔哩"))
    }

    private fun read(kind: KnowledgeEntry.Kind, dir: String, fileName: String): List<KnowledgeEntry> {
        val base = File(root, dir)
        assertTrue("missing $base", base.isDirectory)
        return base.listFiles { file -> file.isDirectory }
            .orEmpty()
            .sortedBy { it.name }
            .map { sub ->
                val text = File(sub, fileName)
                assertTrue("missing $text", text.isFile)
                requireNotNull(KnowledgeStore.parse(kind, sub.name, text.readText())) {
                    "cannot parse $text"
                }
            }
    }
}

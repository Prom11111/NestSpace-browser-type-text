package com.coke.nestspace.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantPresentationTest {

    @Test
    fun plainOpenUsesVisibleApp() {
        assertEquals(
            AssistantPresentation.VISIBLE,
            assistantPresentation(listOf("打开抖音"))
        )
    }

    @Test
    fun openAndSearchUsesVisibleApp() {
        assertEquals(
            AssistantPresentation.VISIBLE,
            assistantPresentation(listOf("打开抖音搜索木雕短视频"))
        )
    }

    @Test
    fun informationRequestUsesHeadlessMirror() {
        assertEquals(
            AssistantPresentation.HEADLESS,
            assistantPresentation(listOf("帮我获取抖音首页正在推荐哪些热门视频"))
        )
    }

    @Test
    fun openAndInspectUsesHeadlessMirror() {
        assertEquals(
            AssistantPresentation.HEADLESS,
            assistantPresentation(listOf("打开抖音看看首页有哪些内容"))
        )
    }
}

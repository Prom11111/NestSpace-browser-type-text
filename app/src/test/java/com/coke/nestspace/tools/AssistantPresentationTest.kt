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

    @Test
    fun openAndSendTitlesUsesHeadlessMirror() {
        assertEquals(
            AssistantPresentation.HEADLESS,
            assistantPresentation(listOf("打开抖音把末日题材的标题发我"))
        )
    }

    @Test
    fun policyAppliesToOtherApps() {
        assertEquals(
            AssistantPresentation.HEADLESS,
            assistantPresentation(listOf("打开微信把群聊标题发我"))
        )
        assertEquals(
            AssistantPresentation.VISIBLE,
            assistantPresentation(listOf("打开淘宝搜索保温杯"))
        )
        assertEquals(
            AssistantPresentation.HEADLESS,
            assistantPresentation(listOf("打开知乎看看热榜有哪些内容"))
        )
    }
}

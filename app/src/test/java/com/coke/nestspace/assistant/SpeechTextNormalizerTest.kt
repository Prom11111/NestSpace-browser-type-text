package com.coke.nestspace.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextNormalizerTest {

    @Test
    fun removesSpacesBetweenChineseTokens() {
        assertEquals(
            "打开抖音把末日题材的标题发我",
            normalizeSpeechText("打 开 抖 音 把 末 日 题 材 的 标 题 发 我")
        )
    }

    @Test
    fun removesSpacesAroundChinesePunctuationAndNumbers() {
        assertEquals(
            "打开知乎，看看第1个结果",
            normalizeSpeechText("打 开 知 乎 ， 看 看 第 1 个 结 果")
        )
    }

    @Test
    fun keepsNormalEnglishWordSpacing() {
        assertEquals(
            "open the app and search",
            normalizeSpeechText("open the  app and search")
        )
    }
}

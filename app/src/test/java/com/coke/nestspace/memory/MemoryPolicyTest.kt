package com.coke.nestspace.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPolicyTest {

    @Test
    fun rejectsSensitiveContent() {
        assertNotNull(MemoryPolicy.sensitiveReason("我的密码是 hunter2"))
        assertNotNull(MemoryPolicy.sensitiveReason("验证码 123456"))
        assertNotNull(MemoryPolicy.sensitiveReason("api key sk-abcdefghijklmnop"))
        assertNotNull(MemoryPolicy.sensitiveReason("银行卡 6222021234567890123"))
        assertNull(MemoryPolicy.sensitiveReason("我喜欢深色主题"))
    }

    @Test
    fun allowsInstructionsThatMentionSecretsWithoutValues() {
        assertNull(MemoryPolicy.sensitiveReason("登录时把密码输入交给用户处理"))
        assertNull(MemoryPolicy.sensitiveReason("不要保存银行卡或验证码"))
        assertNull(MemoryPolicy.sensitiveReason("调用 API Key 之前先确认用户意图"))
        assertNotNull(MemoryPolicy.sensitiveReason("密码是 hunter2"))
        assertNotNull(MemoryPolicy.sensitiveReason("验证码：123456"))
    }

    @Test
    fun normalizesAndExtractsKeywords() {
        assertEquals("喜欢 深色主题", MemoryPolicy.normalize("  喜欢   深色主题 "))
        val keywords = MemoryPolicy.normalizeKeywords(emptyList(), "深色主题 界面简洁")
        assertTrue(keywords.isNotEmpty())
        assertTrue(keywords.contains("深色主题"))
    }

    @Test
    fun skillNamesAreSafeAndStable() {
        assertEquals("bilibili-search", SkillPolicy.normalizeName("Bilibili Search"))
        assertTrue(SkillPolicy.isValidName("bilibili-search"))
        assertFalse(SkillPolicy.isValidName(""))
        assertFalse(SkillPolicy.isValidName("../escape"))
    }
}

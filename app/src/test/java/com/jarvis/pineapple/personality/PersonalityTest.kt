package com.jarvis.pineapple.personality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试：Personality 默认人格
 */
class PersonalityTest {

    @Test
    fun `default personality has expected identity`() {
        val p = Personality.DEFAULT
        assertEquals("JARVIS", p.name)
        assertEquals("Just A Rather Very Intelligent System", p.fullName)
        assertEquals("中文", p.defaultLanguage)
        assertEquals("先生", p.userTitle)
        assertEquals("我", p.selfReference)
    }

    @Test
    fun `default system prompt contains core principles`() {
        val prompt = DEFAULT_SYSTEM_PROMPT
        assertTrue(prompt.contains("JARVIS"))
        assertTrue(prompt.contains("核心原则"))
        assertTrue(prompt.contains("尊重生命"))
        assertTrue(prompt.contains("保护用户"))
        assertTrue(prompt.contains("绝对忠诚"))
        assertTrue(prompt.contains("诚实可信"))
        assertTrue(prompt.contains("守护隐私"))
    }

    @Test
    fun `default system prompt contains wake response`() {
        val prompt = DEFAULT_SYSTEM_PROMPT
        assertTrue(prompt.contains("唤醒响应"))
        assertTrue(prompt.contains("在，先生/女士"))
    }

    @Test
    fun `default system prompt contains taboo list`() {
        val prompt = DEFAULT_SYSTEM_PROMPT
        assertTrue(prompt.contains("禁忌清单"))
        assertTrue(prompt.contains("不假装知道"))
    }

    @Test
    fun `default system prompt mentions night mode`() {
        val prompt = DEFAULT_SYSTEM_PROMPT
        assertTrue(prompt.contains("夜间模式"))
        assertTrue(prompt.contains("23:00"))
    }

    @Test
    fun `Personality copy keeps name and updates title`() {
        val p = Personality.DEFAULT.copy(userTitle = "女士")
        assertEquals("JARVIS", p.name)
        assertEquals("女士", p.userTitle)
        assertNotNull(p.systemPrompt)
    }
}

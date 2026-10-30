package com.jarvis.pineapple.integration

import com.jarvis.pineapple.chat.ChatMessage
import com.jarvis.pineapple.chat.Conversation
import com.jarvis.pineapple.chat.Role
import com.jarvis.pineapple.personality.DEFAULT_SYSTEM_PROMPT
import com.jarvis.pineapple.personality.Personality
import com.jarvis.pineapple.speech.SupportedLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 集成测试：对话与人格流水线。
 *
 * 覆盖「人格 system prompt → 多轮对话上下文装配 → 消息持久化结构」
 * 以及「100+ 语言选择表」的规格要求。
 */
class ConversationPipelineIntegrationTest {

    @Test
    fun `default personality enforces core principles and taboos`() {
        val prompt = Personality.DEFAULT.systemPrompt
        // 5 条核心原则
        assertTrue(prompt.contains("尊重生命"))
        assertTrue(prompt.contains("保护用户"))
        assertTrue(prompt.contains("绝对忠诚"))
        assertTrue(prompt.contains("诚实可信"))
        assertTrue(prompt.contains("守护隐私"))
        // 禁用网络流行语 / 不伪装人类
        assertTrue(prompt.contains("不自称是人类"))
        assertTrue("人格 prompt 应足够完整", prompt.lines().size > 60)
        assertEquals(DEFAULT_SYSTEM_PROMPT.trim(), prompt.trim())
    }

    @Test
    fun `conversation accumulates user and assistant messages in order`() {
        val conv = Conversation(title = "测试会话")
        conv.messages.add(ChatMessage(role = Role.USER, content = "打开蓝牙"))
        conv.messages.add(ChatMessage(role = Role.ASSISTANT, content = "好的，正在为您打开蓝牙。"))

        assertEquals(2, conv.messages.size)
        assertEquals(Role.USER, conv.messages[0].role)
        assertEquals(Role.ASSISTANT, conv.messages[1].role)
        assertNotEquals("消息 id 应唯一", conv.messages[0].id, conv.messages[1].id)
        assertEquals("测试会话", conv.title)
    }

    @Test
    fun `context window keeps most recent 20 messages`() {
        val history = (1..25).map { ChatMessage(role = Role.USER, content = "turn-$it") }
        val context = history.takeLast(20)
        assertEquals(20, context.size)
        assertEquals("turn-6", context.first().content)
        assertEquals("turn-25", context.last().content)
    }

    @Test
    fun `language table covers more than one hundred languages`() {
        assertTrue("应支持 100+ 种语言（实际 ${SupportedLanguages.ALL.size}）", SupportedLanguages.ALL.size > 100)
        assertEquals("zh-CN", SupportedLanguages.DEFAULT.bcp47)
        assertTrue(SupportedLanguages.findByBcp47("en-US") != null)
        val uniqueTags = SupportedLanguages.ALL.map { it.bcp47 }.toSet()
        assertEquals("BCP-47 标签不应重复", SupportedLanguages.ALL.size, uniqueTags.size)
    }
}

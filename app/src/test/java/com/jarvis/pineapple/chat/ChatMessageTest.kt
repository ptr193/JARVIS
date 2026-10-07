package com.jarvis.pineapple.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试：ChatMessage / Conversation / Role
 */
class ChatMessageTest {

    @Test
    fun `ChatMessage default values are set`() {
        val msg = ChatMessage(role = Role.USER, content = "你好")
        assertTrue(msg.id.startsWith("msg_"))
        assertEquals("你好", msg.content)
        assertEquals(Role.USER, msg.role)
        assertFalse(msg.isStreaming)
        assertTrue(msg.timestamp > 0)
    }

    @Test
    fun `generateId produces unique ids`() {
        val ids = (1..1000).map { ChatMessage.generateId() }.toSet()
        assertEquals(1000, ids.size)
    }

    @Test
    fun `copy preserves id and updates content`() {
        val original = ChatMessage(role = Role.ASSISTANT, content = "partial")
        val updated = original.copy(content = "complete")
        assertEquals(original.id, updated.id)
        assertEquals("complete", updated.content)
    }

    @Test
    fun `Conversation default title and messages`() {
        val conv = Conversation()
        assertTrue(conv.id.startsWith("conv_"))
        assertEquals("新对话", conv.title)
        assertNotNull(conv.messages)
        assertTrue(conv.messages.isEmpty())
    }

    @Test
    fun `Conversation messages can be mutated`() {
        val conv = Conversation()
        conv.messages.add(ChatMessage(role = Role.USER, content = "hi"))
        assertEquals(1, conv.messages.size)
        conv.messages.clear()
        assertEquals(0, conv.messages.size)
    }

    @Test
    fun `Role enum has all three values`() {
        assertEquals(3, Role.values().size)
        assertEquals(Role.USER, Role.valueOf("USER"))
        assertEquals(Role.ASSISTANT, Role.valueOf("ASSISTANT"))
        assertEquals(Role.SYSTEM, Role.valueOf("SYSTEM"))
    }
}

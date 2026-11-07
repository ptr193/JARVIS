package com.jarvis.pineapple.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun createSwitchAndDeleteConversations() {
        val repo = ConversationRepository(conversationsDirOverride = tmp.root)
        val first = repo.createConversation("会话一")
        first.messages.add(ChatMessage(role = Role.USER, content = "你好"))
        repo.updateMessages(first.messages.toList())
        val second = repo.createConversation("会话二")
        assertEquals(second.id, repo.currentConversationId.value)
        repo.switchConversation(first.id)
        assertEquals(first.id, repo.getCurrentConversation()?.id)
        assertEquals("你好", repo.getCurrentConversation()?.messages?.first()?.content)
        repo.deleteConversation(first.id)
        assertEquals(1, repo.conversations.value.size)
        assertEquals(second.id, repo.currentConversationId.value)
    }

    @Test
    fun persistMessagesToCurrentConversationFile() {
        val dir = tmp.newFolder("conversations")
        val repo = ConversationRepository(conversationsDirOverride = dir)
        val conv = repo.createConversation("持久化")
        val messages = listOf(
            ChatMessage(role = Role.USER, content = "今天天气"),
            ChatMessage(role = Role.ASSISTANT, content = "晴")
        )
        repo.updateMessages(messages)
        val reloaded = ConversationRepository(conversationsDirOverride = dir)
        val loaded = reloaded.conversations.value.first { it.id == conv.id }
        assertEquals(2, loaded.messages.size)
        assertEquals("今天天气", loaded.messages[0].content)
        assertEquals("晴", loaded.messages[1].content)
        assertTrue(dir.listFiles()!!.any { it.name == "${conv.id}.json" })
    }
}

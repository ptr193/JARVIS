package com.jarvis.pineapple.chat

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test
    fun persistLongContextWithoutTruncatingContent() {
        val dir = tmp.newFolder("long-context")
        val repo = ConversationRepository(conversationsDirOverride = dir)
        repo.createConversation("长上下文")
        val payload = "汉".repeat(8000)
        val messages = (1..40).map { idx ->
            ChatMessage(
                role = if (idx % 2 == 0) Role.ASSISTANT else Role.USER,
                content = "$idx:$payload"
            )
        }
        repo.updateMessages(messages)
        val reloaded = ConversationRepository(conversationsDirOverride = dir)
        val loaded = reloaded.getCurrentConversation() ?: reloaded.conversations.value.first()
        assertEquals(40, loaded.messages.size)
        assertEquals(8002, loaded.messages.first().content.length)
        assertTrue(loaded.messages.last().content.startsWith("40:"))
        assertEquals(payload, loaded.messages.last().content.substringAfter(":"))
    }

    @Test
    fun concurrentCreatesStayConsistent() {
        val dir = tmp.newFolder("concurrent")
        val repo = ConversationRepository(conversationsDirOverride = dir)
        val workers = 8
        val perWorker = 10
        val pool = Executors.newFixedThreadPool(workers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        repeat(workers) { worker ->
            pool.execute {
                start.await()
                repeat(perWorker) { idx ->
                    repo.createConversation("w$worker-$idx")
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(workers * perWorker, repo.conversations.value.size)
        assertEquals(workers * perWorker, dir.listFiles { f -> f.extension == "json" }!!.size)
    }
}

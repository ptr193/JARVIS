package com.jarvis.pineapple.chat

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val first = requireNotNull(repo.createConversation("会话一"))
        first.messages.add(ChatMessage(role = Role.USER, content = "你好"))
        repo.updateMessages(first.messages.toList())
        val second = requireNotNull(repo.createConversation("会话二"))
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
        val conv = requireNotNull(repo.createConversation("持久化"))
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
        requireNotNull(repo.createConversation("长上下文"))
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
        val reloaded = ConversationRepository(conversationsDirOverride = dir)
        assertEquals(workers * perWorker, reloaded.conversations.value.size)
        assertEquals(repo.conversations.value.map { it.id }.toSet(), reloaded.conversations.value.map { it.id }.toSet())
    }

    @Test
    fun saveFailureIsVisibleAndDoesNotReplacePersistedState() {
        val dir = tmp.newFolder("save-blocked")
        val repo = ConversationRepository(conversationsDirOverride = dir)
        val conversation = requireNotNull(repo.createConversation("保留原状态"))
        assertTrue(File(dir, "${conversation.id}.json").delete())
        assertTrue(dir.delete())
        dir.writeText("not-a-directory")

        assertFalse(repo.updateMessages(listOf(ChatMessage(role = Role.USER, content = "未保存"))))
        assertTrue(repo.getCurrentConversation()?.messages?.isEmpty() == true)
        assertTrue(repo.lastError.value?.contains("保存失败") == true)
    }

    @Test
    fun deleteFailureRetainsConversationAndReportsError() {
        val dir = tmp.newFolder("delete-blocked")
        val repo = ConversationRepository(conversationsDirOverride = dir)
        val conversation = requireNotNull(repo.createConversation("不能静默删除"))
        val file = File(dir, "${conversation.id}.json")
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        File(file, "child").writeText("prevent delete")

        assertFalse(repo.deleteConversation(conversation.id))
        assertEquals(1, repo.conversations.value.size)
        assertTrue(repo.lastError.value?.contains("删除失败") == true)
    }

    @Test
    fun malformedConversationIsReportedDuringLoad() {
        val dir = tmp.newFolder("malformed")
        File(dir, "broken.json").writeText("{not-json")

        val repo = ConversationRepository(conversationsDirOverride = dir)

        assertTrue(repo.conversations.value.isEmpty())
        assertTrue(repo.lastError.value?.contains("读取失败") == true)
    }

    @Test
    fun archivePersistsAndSurvivesExportImportRoundTrip() {
        val dir = tmp.newFolder("archive")
        val repo = ConversationRepository(conversationsDirOverride = dir)
        val conv = requireNotNull(repo.createConversation("归档会话"))
        conv.messages.add(ChatMessage(role = Role.USER, content = "hi"))
        repo.updateMessages(conv.messages.toList())

        assertTrue(repo.setArchived(conv.id, true))
        val reloaded = ConversationRepository(conversationsDirOverride = dir)
        assertTrue(reloaded.conversations.value.first { it.id == conv.id }.archived)

        val exported = repo.exportAll()
        val targetRepo = ConversationRepository(conversationsDirOverride = tmp.newFolder("archive-import"))
        assertTrue(targetRepo.importFromJson(exported))
        val imported = targetRepo.conversations.value.first { it.id == conv.id }
        assertTrue(imported.archived)
        assertEquals("hi", imported.messages.first().content)

        assertTrue(repo.setArchived(conv.id, false))
        assertFalse(repo.conversations.value.first { it.id == conv.id }.archived)
    }

    @Test
    fun importAcceptsSingleConversationExport() {
        val source = ConversationRepository(conversationsDirOverride = tmp.newFolder("single-src"))
        val conv = requireNotNull(source.createConversation("单会话"))
        val singleJson = source.exportConversation(conv.id)
        assertTrue(singleJson != null)

        val target = ConversationRepository(conversationsDirOverride = tmp.newFolder("single-dst"))
        assertTrue(target.importFromJson(singleJson!!))
        assertEquals("单会话", target.conversations.value.single().title)
    }

    @Test
    fun malformedImportFailsHonestly() {
        val repo = ConversationRepository(conversationsDirOverride = tmp.newFolder("bad-import"))
        assertFalse(repo.importFromJson("{oops"))
        assertTrue(repo.lastError.value?.contains("导入失败") == true)
    }

    @Test
    fun createFailureKeepsFlowsAndCurrentId() {
        val dir = tmp.newFolder("create-blocked")
        val repo = ConversationRepository(conversationsDirOverride = dir)
        val existing = requireNotNull(repo.createConversation("原会话"))
        val previous = repo.conversations.value
        assertTrue(File(dir, "${existing.id}.json").delete())
        assertTrue(dir.delete())
        dir.writeText("blocked")

        assertNull(repo.createConversation("不能出现"))
        assertEquals(previous, repo.conversations.value)
        assertEquals(existing.id, repo.currentConversationId.value)
        assertTrue(repo.lastError.value?.contains("保存失败") == true)
    }

    @Test
    fun importFailureRollsBackOverwritesAndNewFiles() {
        val dir = tmp.newFolder("import-rollback")
        val repo = ConversationRepository(conversationsDirOverride = dir)
        val existing = requireNotNull(repo.createConversation("原始标题"))
        assertTrue(repo.updateMessages(listOf(ChatMessage(role = Role.USER, content = "原始内容"))))
        val blocked = File(dir, "blocked.json")
        assertTrue(blocked.mkdir())
        File(blocked, "child").writeText("prevent rename")
        val batch = listOf(
            ConversationDto(
                id = existing.id,
                title = "错误覆盖",
                messages = listOf(MessageDto("new", "USER", "错误内容", 2L, false)),
                createdAt = existing.createdAt
            ),
            ConversationDto(id = "new_safe", title = "应撤销的新会话", messages = emptyList(), createdAt = 2L),
            ConversationDto(id = "blocked", title = "半批会话", messages = emptyList(), createdAt = 3L)
        )

        assertFalse(repo.importFromJson(Json.encodeToString(batch)))

        val inMemory = repo.conversations.value.single { it.id == existing.id }
        assertEquals("原始标题", inMemory.title)
        assertEquals("原始内容", inMemory.messages.single().content)
        assertFalse(repo.conversations.value.any { it.id == "blocked" || it.id == "new_safe" })
        assertFalse(File(dir, "new_safe.json").exists())
        val reloaded = ConversationRepository(conversationsDirOverride = dir)
        val onDisk = reloaded.conversations.value.single { it.id == existing.id }
        assertEquals("原始标题", onDisk.title)
        assertEquals("原始内容", onDisk.messages.single().content)
        assertFalse(reloaded.conversations.value.any { it.id == "blocked" || it.id == "new_safe" })
        assertTrue(repo.lastError.value?.contains("本批未生效") == true)
    }

    @Test
    fun importRejectsUnsafeIdsInvalidRolesAndDuplicateIdsBeforeWriting() {
        val dir = tmp.newFolder("import-validation")
        val repo = ConversationRepository(conversationsDirOverride = dir)
        listOf("../escape", "..", "a/b", ".hidden", "a b").forEach { id ->
            assertFalse("应拒绝 ID：$id", repo.importFromJson(Json.encodeToString(listOf(testDto(id)))))
        }
        assertFalse(File(dir.parentFile, "escape.json").exists())
        assertTrue(repo.conversations.value.isEmpty())

        val invalidRole = """
            [{"id":"valid","title":"坏角色","messages":[{"id":"m","role":"OWNER","content":"x","timestamp":1,"isStreaming":false}],"createdAt":1,"archived":false}]
        """.trimIndent()
        assertFalse(repo.importFromJson(invalidRole))
        assertFalse(repo.importFromJson(Json.encodeToString(listOf(testDto("duplicate"), testDto("duplicate")))))
        assertTrue(repo.conversations.value.isEmpty())
        assertTrue(dir.listFiles { f -> f.extension == "json" }!!.isEmpty())
    }

    private fun testDto(id: String) = ConversationDto(
        id = id,
        title = "导入",
        messages = listOf(MessageDto("message", "USER", "内容", 1L, false)),
        createdAt = 1L
    )
}

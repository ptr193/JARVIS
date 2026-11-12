package com.jarvis.pineapple.load

import com.jarvis.pineapple.assistant.AssistStructureReader
import com.jarvis.pineapple.assistant.OverlayCameraController
import com.jarvis.pineapple.assistant.OverlayReplyCoordinator
import com.jarvis.pineapple.assistant.SuggestionCardPublisher
import com.jarvis.pineapple.autonomous.MemorySystem
import com.jarvis.pineapple.chat.AssistantCommandParser
import com.jarvis.pineapple.chat.ChatMessage
import com.jarvis.pineapple.chat.ConversationRepository
import com.jarvis.pineapple.chat.Role
import com.jarvis.pineapple.perception.CameraCaptureSession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ProgressiveLoadTest(private val round: Int) {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun progressiveRound() {
        val profile = LoadProfile.forRound(round)
        val started = System.nanoTime()
        runCore(profile)
        if (!profile.coreOnly) runFull(profile)
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(
            "第 $round 轮耗时 ${elapsed}ms，超过预算 ${profile.maxMillis}ms",
            elapsed <= profile.maxMillis
        )
    }

    private fun runCore(profile: LoadProfile) {
        val spoken = "打开蓝牙"
        val screen = "屏".repeat(profile.overlayChars)
        val prompt = OverlayReplyCoordinator.buildPrompt(spoken, screen)
        assertTrue(prompt.endsWith("用户说：$spoken"))
        assertEquals("", OverlayReplyCoordinator.buildPrompt("  ", screen))
        assertTrue(OverlayReplyCoordinator.shouldSpeak("可以"))
        assertFalse(OverlayReplyCoordinator.shouldSpeak(" "))

        val chunks = (1..profile.overlayChunks).map { "块$it" }
        val joined = runBlocking { OverlayReplyCoordinator.collectReply(flowOf(*chunks.toTypedArray())) }
        assertEquals(chunks.joinToString(""), joined)

        val volume = AssistantCommandParser.parse("音量 80")
        assertEquals("SET_VOLUME", volume.action)
        assertTrue(volume.isExecutable)
        assertEquals(12, AssistantCommandParser.volumeFromPercent(80))
        assertTrue(AssistantCommandParser.isDirectCommand("音量 80"))

        val dir = tmp.newFolder("core-$round")
        val repo = ConversationRepository(conversationsDirOverride = dir)
        val conv = repo.createConversation("负荷$round")
        val payload = "汉".repeat(profile.payloadChars.coerceAtLeast(1))
        val messages = (1..profile.messagesPerConversation).map { idx ->
            ChatMessage(
                role = if (idx % 2 == 0) Role.ASSISTANT else Role.USER,
                content = "$idx:$payload"
            )
        }
        repo.updateMessages(messages)
        val reloaded = ConversationRepository(conversationsDirOverride = dir)
        val loaded = reloaded.conversations.value.first { it.id == conv.id }
        assertEquals(profile.messagesPerConversation, loaded.messages.size)
        assertEquals(messages.first().content, loaded.messages.first().content)
        assertEquals(messages.last().content, loaded.messages.last().content)
    }

    private fun runFull(profile: LoadProfile) {
        LoadProfile.COMMANDS.forEach { command ->
            repeat(profile.commandRepeats) {
                val parsed = AssistantCommandParser.parse(command)
                assertTrue("$command 第 $round 轮应可解析", parsed.action != "UNKNOWN")
            }
        }
        val unknown = AssistantCommandParser.parse("今天天气怎么样")
        assertEquals("UNKNOWN", unknown.action)
        assertFalse(AssistantCommandParser.isDirectCommand("我想打开窗户透透气，顺便聊聊今天天气"))

        val nodes = (1..profile.screenNodes).map { "节点$it" }
        val screenText = AssistStructureReader.collectTexts(nodes.asSequence())
        assertTrue(screenText.startsWith("节点1"))
        assertTrue(screenText.endsWith("节点${profile.screenNodes}"))
        assertEquals(AssistStructureReader.EMPTY_SCREEN, AssistStructureReader.messageFor("  "))

        val ids = (1..profile.suggestions).map { "s$it" }
        val fingerprint = SuggestionCardPublisher.fingerprint(ids, "2026-11-12")
        assertTrue(SuggestionCardPublisher.shouldPublish(null, fingerprint, ids.size))
        assertFalse(SuggestionCardPublisher.shouldPublish(fingerprint, fingerprint, ids.size))
        assertEquals(
            CameraCaptureSession.Task.OBJECT,
            OverlayCameraController.resolveTask("unknown")
        )
        assertEquals(
            CameraCaptureSession.Task.DOCUMENT,
            OverlayCameraController.resolveTask(OverlayCameraController.TASK_DOCUMENT)
        )
        assertEquals(CameraCaptureSession.NO_IMAGE, OverlayCameraController.noImageMessage())

        val memoryDir = tmp.newFolder("memory-$round")
        val memory = MemorySystem(File(memoryDir, "memories.json"), File(memoryDir, "archive"))
        repeat(profile.memories) { idx ->
            memory.recordConversation("负荷记忆$round-$idx 蓝牙 音量")
        }
        if (profile.memories > 0) {
            val hits = memory.search("蓝牙")
            assertTrue("第 $round 轮记忆检索应命中", hits.isNotEmpty())
            assertTrue(memory.buildMemoryContext("蓝牙音量").contains("历史记忆参考"))
        }
        if (profile.memories > 1000) {
            assertTrue("归档后记忆应不超过上限", memory.getStats().totalCount <= 1000)
        }

        val convDir = tmp.newFolder("full-conv-$round")
        val repo = ConversationRepository(conversationsDirOverride = convDir)
        val payload = "文".repeat(profile.payloadChars.coerceAtLeast(1))
        repeat(profile.conversations) { idx ->
            repo.createConversation("全功能$round-$idx")
            val messages = (1..profile.messagesPerConversation).map { msg ->
                ChatMessage(
                    role = if (msg % 2 == 0) Role.ASSISTANT else Role.USER,
                    content = "$idx-$msg:$payload"
                )
            }
            repo.updateMessages(messages)
        }
        assertEquals(profile.conversations, repo.conversations.value.size)

        if (profile.workers > 1 && profile.createsPerWorker > 0) {
            val concurrentDir = tmp.newFolder("concurrent-$round")
            val concurrentRepo = ConversationRepository(conversationsDirOverride = concurrentDir)
            val pool = Executors.newFixedThreadPool(profile.workers)
            val start = CountDownLatch(1)
            val done = CountDownLatch(profile.workers)
            repeat(profile.workers) { worker ->
                pool.execute {
                    start.await()
                    repeat(profile.createsPerWorker) { idx ->
                        concurrentRepo.createConversation("c$round-$worker-$idx")
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(profile.maxMillis, TimeUnit.MILLISECONDS))
            pool.shutdownNow()
            val expected = profile.workers * profile.createsPerWorker
            assertEquals(expected, concurrentRepo.conversations.value.size)
            val ids = concurrentRepo.conversations.value.map { it.id }.toSet()
            assertEquals("会话 id 必须唯一", expected, ids.size)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "round={0}")
        fun rounds(): Collection<Array<Any>> = (1..30).map { arrayOf(it as Any) }
    }
}

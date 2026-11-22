package com.jarvis.pineapple.chat

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.jarvis.pineapple.autonomous.MemorySystem
import com.jarvis.pineapple.db.JarvisDatabase
import com.jarvis.pineapple.model.AiModel
import com.jarvis.pineapple.model.ModelCapabilityScheduler
import com.jarvis.pineapple.model.ModelInfo
import com.jarvis.pineapple.model.ModelScheduler
import com.jarvis.pineapple.model.ModelSource
import com.jarvis.pineapple.model.ModelState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatEngineBehaviorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var fixtureCounter = 0
    private val memoryDrivers = mutableListOf<JdbcSqliteDriver>()

    @After
    fun closeMemoryDrivers() {
        memoryDrivers.forEach { it.close() }
    }

    @Test
    fun createFailureReturnsChineseErrorWithoutExecutingCommandOrModel() {
        val model = FakeModel(usable = true)
        val fixture = Fixture(model = model)
        blockDirectory(fixture.conversationDir)

        val reply = fixture.send("root 执行 id")

        assertTrue(reply.contains("无法创建当前会话"))
        assertTrue(reply.contains("未执行命令或模型"))
        assertTrue(fixture.root.commands.isEmpty())
        assertEquals(0, model.loadCount)
        assertTrue(fixture.engine.messages.value.isEmpty())
        assertNull(fixture.repo.currentConversationId.value)
        assertTrue(fixture.repo.conversations.value.isEmpty())
    }

    @Test
    fun newSwitchDeleteClearAndImportInvalidatePendingDangerousAction() {
        listOf("new", "switch", "delete", "clear", "import").forEach { operation ->
            val fixture = Fixture()
            assertTrue(fixture.engine.newConversation())
            val firstId = requireNotNull(fixture.repo.currentConversationId.value)
            var secondId: String? = null
            if (operation == "switch" || operation == "delete") {
                assertTrue(fixture.engine.newConversation())
                secondId = fixture.repo.currentConversationId.value
                assertTrue(fixture.engine.switchConversation(firstId))
            }
            val prompt = fixture.send(ROOT_DANGEROUS)
            assertTrue(operation, prompt.contains("二次确认"))
            assertTrue(operation, fixture.root.commands.isEmpty())

            when (operation) {
                "new" -> assertTrue(fixture.engine.newConversation())
                "switch" -> assertTrue(fixture.engine.switchConversation(requireNotNull(secondId)))
                "delete" -> assertTrue(fixture.engine.deleteConversation(firstId))
                "clear" -> assertTrue(fixture.engine.clearConversation())
                "import" -> assertTrue(
                    fixture.engine.importConversations(
                        Json.encodeToString(
                            listOf(ConversationDto("imported_safe", "导入", emptyList(), Long.MAX_VALUE))
                        )
                    )
                )
            }
            fixture.send("确认执行")

            assertEquals(operation, 0, fixture.root.commands.size)
        }
    }

    @Test
    fun cancelAndNonConfirmationInstructionInvalidatePendingAction() {
        listOf("取消", "普通聊天").forEach { interruption ->
            val fixture = Fixture()
            assertTrue(fixture.send(ROOT_DANGEROUS).contains("二次确认"))
            fixture.send(interruption)
            fixture.send("确认执行")

            assertEquals(interruption, 0, fixture.root.commands.size)
        }
    }

    @Test
    fun rootAuthorizationRevokedBeforeConfirmationPreventsExecution() {
        val fixture = Fixture()
        assertTrue(fixture.send(ROOT_DANGEROUS).contains("二次确认"))
        fixture.root.status = RootAccessStatus(allowed = false, granted = true, detail = "revoked")

        val reply = fixture.send("确认执行")

        assertTrue(reply.contains("授权已撤销"))
        assertTrue(reply.contains("未执行任何命令"))
        assertTrue(fixture.root.commands.isEmpty())
    }

    @Test
    fun vmDeletedBeforeConfirmationPreventsExecution() {
        val fixture = Fixture()
        assertTrue(fixture.send(VM_DANGEROUS).contains("二次确认"))
        assertTrue(fixture.vm.commands.isEmpty())
        fixture.vm.vms.clear()

        val reply = fixture.send("确认执行")

        assertTrue(reply.contains("虚拟机已不存在") || reply.contains("目标虚拟机已不存在"))
        assertTrue(reply.contains("未执行任何命令"))
        assertTrue(fixture.vm.commands.isEmpty())
    }

    @Test
    fun failedSwitchAndDeleteKeepOriginalEngineMessages() {
        val fixture = Fixture()
        assertTrue(fixture.engine.newConversation())
        fixture.send("你好")
        val conversationId = fixture.repo.currentConversationId.value
        val originalMessages = fixture.engine.messages.value

        assertFalse(fixture.engine.switchConversation("missing"))
        assertEquals(conversationId, fixture.repo.currentConversationId.value)
        assertEquals(originalMessages, fixture.engine.messages.value)

        val file = File(fixture.conversationDir, "$conversationId.json")
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        File(file, "child").writeText("prevent delete")
        assertFalse(fixture.engine.deleteConversation(requireNotNull(conversationId)))
        assertEquals(conversationId, fixture.repo.currentConversationId.value)
        assertEquals(originalMessages, fixture.engine.messages.value)
    }

    @Test
    fun executedCommandResultAndConversationSaveFailureAreReportedSeparately() {
        val fixture = Fixture()
        assertTrue(fixture.engine.newConversation())
        blockDirectory(fixture.conversationDir)

        val reply = fixture.send("终端执行 echo ok")

        assertEquals(1, fixture.vm.commands.size)
        assertTrue(reply.contains("vm-output"))
        assertTrue(reply.contains("退出码：0"))
        assertTrue(reply.contains("对话记录保存失败"))
        assertFalse(reply.contains("未执行任何命令"))
        assertTrue(fixture.engine.persistenceError.value?.contains("对话保存失败") == true)
    }

    @Test
    fun noModelAndMemorySaveFailuresAreVisible() {
        val fixture = Fixture()
        assertTrue(fixture.engine.newConversation())
        blockDirectory(fixture.conversationDir)
        blockFile(File(fixture.memoryDir, "memories.json"))

        val reply = fixture.send("你好")

        assertTrue(reply.contains(ModelScheduler.NO_MODEL_MESSAGE))
        assertTrue(reply.contains("对话记录"))
        assertTrue(reply.contains("长期记忆"))
        assertTrue(reply.contains("保存失败"))
        val error = fixture.engine.persistenceError.value.orEmpty()
        assertTrue(error.contains("对话保存失败"))
        assertTrue(error.contains("记忆保存失败"))
    }

    @Test
    fun streamSuccessAndExceptionPersistTerminalStateAndMemory() {
        val success = Fixture(FakeModel(usable = true, streamFactory = { flowOf("你", "好") }))
        assertTrue(success.engine.newConversation())
        assertEquals("你好", success.send("你好"))
        assertFalse(success.engine.messages.value.last().isStreaming)
        assertEquals(1, success.memory.getStats().conversationCount)
        var reloaded = ConversationRepository(conversationsDirOverride = success.conversationDir)
        assertEquals("你好", reloaded.getCurrentConversation()?.messages?.last()?.content)
        assertFalse(reloaded.getCurrentConversation()?.messages?.last()?.isStreaming == true)

        val failure = Fixture(
            FakeModel(
                usable = true,
                streamFactory = { flow { emit("部分"); throw IllegalStateException("boom") } }
            )
        )
        assertTrue(failure.engine.newConversation())
        failure.send("你好")
        val failureReply = failure.engine.messages.value.last().content
        assertTrue(failureReply.contains("部分"))
        assertTrue(failureReply.contains("模型请求失败"))
        assertTrue(failureReply.contains("boom"))
        assertFalse(failure.engine.isGenerating.value)
        assertEquals(1, failure.memory.getStats().conversationCount)
        reloaded = ConversationRepository(conversationsDirOverride = failure.conversationDir)
        assertEquals(failureReply, reloaded.getCurrentConversation()?.messages?.last()?.content)
        assertFalse(reloaded.getCurrentConversation()?.messages?.last()?.isStreaming == true)
    }

    @Test
    fun streamCancellationPersistsPartialTerminalStateAndMemory() {
        val fixture = Fixture(
            FakeModel(
                usable = true,
                streamFactory = {
                    flow {
                        emit("部分")
                        throw CancellationException("cancelled by test")
                    }
                }
            )
        )
        assertTrue(fixture.engine.newConversation())

        runBlocking {
            val stream = fixture.engine.sendUserMessage("你好")
            val job = launch { stream.collect { } }
            withTimeout(2_000) {
                while (!fixture.engine.messages.value.last().content.contains("部分")) yield()
            }
            job.cancel()
            job.join()
        }

        val reply = fixture.engine.messages.value.last().content
        assertTrue(reply.contains("部分"))
        assertTrue(reply.contains("模型请求已取消"))
        assertFalse(fixture.engine.messages.value.last().isStreaming)
        assertFalse(fixture.engine.isGenerating.value)
        assertEquals(1, fixture.memory.getStats().conversationCount)
        val reloaded = ConversationRepository(conversationsDirOverride = fixture.conversationDir)
        assertEquals(reply, reloaded.getCurrentConversation()?.messages?.last()?.content)
    }

    @Test
    fun streamCompletionReportsConversationAndMemoryPersistenceFailures() {
        val fixture = Fixture(FakeModel(usable = true, streamFactory = { flowOf("回复") }))
        assertTrue(fixture.engine.newConversation())
        blockDirectory(fixture.conversationDir)
        blockFile(File(fixture.memoryDir, "memories.json"))

        assertEquals("回复", fixture.send("你好"))

        val visible = fixture.engine.messages.value.last().content
        assertTrue(visible.contains("回复"))
        assertTrue(visible.contains("对话记录保存失败"))
        assertTrue(visible.contains("长期记忆保存失败"))
        assertTrue(fixture.engine.persistenceError.value?.contains("记忆保存失败") == true)
    }

    @Test
    fun sensitiveConversationIsRejectedByLongTermMemory() {
        val fixture = Fixture()
        assertTrue(fixture.engine.newConversation())
        val conversationId = requireNotNull(fixture.repo.currentConversationId.value)

        val reply = fixture.send("我的密码是：CorrectHorseBatteryStaple")

        assertTrue(reply.contains("长期记忆保存失败"))
        assertTrue(fixture.memory.getConversationTurns(conversationId).isEmpty())
        assertTrue(fixture.memory.memories.value.isEmpty())
        assertTrue(fixture.memory.lastError.value?.contains("敏感信息") == true)
    }

    @Test
    fun conversationMemoryStoresCompleteRawTurns() {
        val userText = "用户首行\n" + "用户原文".repeat(1_500)
        val assistantText = "助手首行\n" + "助手原文".repeat(1_500)
        val fixture = Fixture(
            FakeModel(usable = true, streamFactory = { flowOf(assistantText) })
        )
        assertTrue(fixture.engine.newConversation())
        val conversationId = requireNotNull(fixture.repo.currentConversationId.value)

        assertEquals(assistantText, fixture.send(userText))

        val turns = fixture.memory.getConversationTurns(conversationId)
        assertEquals(2, turns.size)
        assertEquals(userText, turns.single { it.role == "USER" }.content)
        assertEquals(assistantText, turns.single { it.role == "ASSISTANT" }.content)
        assertTrue(turns.all { it.conversationId == conversationId })
    }

    @Test
    fun deleteMessageRemovesOnlyTargetAndPersists() {
        val fixture = Fixture(model = FakeModel(usable = true))
        assertTrue(fixture.engine.newConversation())
        runBlocking { fixture.engine.sendUserMessage("第一条").toList() }
        runBlocking { fixture.engine.sendUserMessage("第二条").toList() }
        val target = fixture.engine.messages.value.first { it.role == Role.USER }

        assertTrue(fixture.engine.deleteMessage(target.id))
        assertFalse(fixture.engine.messages.value.any { it.id == target.id })
        val reloaded = ConversationRepository(conversationsDirOverride = fixture.conversationDir)
        val persisted = reloaded.conversations.value.first()
        assertFalse(persisted.messages.any { it.id == target.id })
    }

    @Test
    fun prepareRegenerationRemovesLastAssistantAndReusesUserInput() {
        val fixture = Fixture(model = FakeModel(usable = true))
        assertTrue(fixture.engine.newConversation())
        runBlocking { fixture.engine.sendUserMessage("你好").toList() }
        assertEquals(2, fixture.engine.messages.value.size)

        assertEquals("你好", fixture.engine.prepareRegeneration())
        assertEquals(1, fixture.engine.messages.value.size)
        assertEquals(Role.USER, fixture.engine.messages.value.single().role)
        assertNull(fixture.engine.prepareRegeneration())
    }

    @Test
    fun imageOnTextModelReturnsExplicitUnsupportedMessage() {
        val fixture = Fixture(model = FakeModel(usable = true))
        assertTrue(fixture.engine.newConversation())

        val reply = runBlocking {
            fixture.engine.sendUserMessage(
                "看这张图",
                image = com.jarvis.pineapple.model.ImageInput("QUJD", "image/png")
            ).toList().joinToString("")
        }

        assertTrue(reply.contains("不支持图片输入"))
    }

    private inner class Fixture(
        private val model: FakeModel = FakeModel(usable = false)
    ) {
        val conversationDir: File = tmp.newFolder("chat-${fixtureCounter++}")
        val memoryDir: File = File(conversationDir.parentFile, "${conversationDir.name}-memory").apply { mkdirs() }
        val repo = ConversationRepository(conversationsDirOverride = conversationDir)
        val memory = MemorySystem(
            File(memoryDir, "memories.json"),
            File(memoryDir, "archive"),
            newMemoryDatabase()
        )
        val vm = FakeVmBridge()
        val root = FakeRootBridge()
        private val scheduler = ModelScheduler(model)
        val engine = ChatEngine.createForTest(
            modelScheduler = scheduler,
            conversationRepository = repo,
            memorySystem = memory,
            capabilityScheduler = ModelCapabilityScheduler(scheduler),
            conversationEnhancer = ConversationEnhancer(TranslationEngine(apiBaseUrl = "")),
            commandExecutor = null,
            vmCommandBridge = vm,
            rootCommandBridge = root,
            systemPromptProvider = { "测试人格" }
        )

        fun send(content: String): String = runBlocking {
            engine.sendUserMessage(content).toList().joinToString("")
        }
    }

    private class FakeModel(
        usable: Boolean,
        private val streamFactory: () -> Flow<String> = { flowOf("模型回复") }
    ) : AiModel {
        private val usable = usable
        override val info = ModelInfo("fake", "Fake", ModelSource.LOCAL_USER)
        override var state: ModelState = if (usable) ModelState.READY else ModelState.ERROR
        var loadCount = 0
            private set

        override suspend fun load() {
            loadCount++
            if (usable) state = ModelState.READY
        }

        override suspend fun unload() {
            state = ModelState.UNLOADED
        }

        override suspend fun generate(prompt: String, systemPrompt: String?): String = "模型回复"

        override fun stream(prompt: String, systemPrompt: String?): Flow<String> = streamFactory()
    }

    private class FakeVmBridge : VmCommandBridge {
        val vms = mutableListOf(VmSummary("vm-1", "Ubuntu", running = true))
        val commands = mutableListOf<Pair<String, String>>()

        override suspend fun listVms(): List<VmSummary> = vms.toList()

        override suspend fun start(vmId: String): Boolean = true

        override suspend fun run(vmId: String, command: String): VmRunResult {
            commands += vmId to command
            return VmRunResult(output = "vm-output", exitCode = 0)
        }

        override suspend fun openDesktop(vmId: String): VmDesktopActionResult =
            VmDesktopActionResult(ok = true, message = "desktop")

        override suspend fun openBrowser(vmId: String): VmDesktopActionResult =
            VmDesktopActionResult(ok = true, message = "browser")
    }

    private class FakeRootBridge : RootCommandBridge {
        var status = RootAccessStatus(allowed = true, granted = true, detail = "ok")
        val commands = mutableListOf<String>()

        override suspend fun status(): RootAccessStatus = status

        override suspend fun run(command: String): RootRunResult {
            commands += command
            return RootRunResult(output = "root-output", exitCode = 0)
        }
    }

    private fun newMemoryDatabase(): JarvisDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        memoryDrivers += driver
        JarvisDatabase.Schema.create(driver)
        return JarvisDatabase(driver)
    }

    private fun blockDirectory(directory: File) {
        assertTrue(directory.deleteRecursively())
        directory.writeText("not a directory")
    }

    private fun blockFile(file: File) {
        if (file.exists()) assertTrue(file.deleteRecursively())
        assertTrue(file.mkdir())
        File(file, "child").writeText("prevent rename")
    }

    private companion object {
        const val ROOT_DANGEROUS = "root 执行 rm -rf /"
        const val VM_DANGEROUS = "终端执行 rm -rf /"
    }
}

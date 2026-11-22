package com.jarvis.pineapple.load

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.jarvis.pineapple.autonomous.MemorySystem
import com.jarvis.pineapple.chat.ChatEngine
import com.jarvis.pineapple.chat.ChatMessage
import com.jarvis.pineapple.chat.ConversationDto
import com.jarvis.pineapple.chat.ConversationEnhancer
import com.jarvis.pineapple.chat.ConversationRepository
import com.jarvis.pineapple.chat.Role
import com.jarvis.pineapple.chat.RootAccessStatus
import com.jarvis.pineapple.chat.RootCommandBridge
import com.jarvis.pineapple.chat.RootRunResult
import com.jarvis.pineapple.chat.TranslationEngine
import com.jarvis.pineapple.chat.VmCommandBridge
import com.jarvis.pineapple.chat.VmDesktopActionResult
import com.jarvis.pineapple.chat.VmRunResult
import com.jarvis.pineapple.chat.VmSummary
import com.jarvis.pineapple.cloud.CloudSessionStore
import com.jarvis.pineapple.cloud.CloudSyncManager
import com.jarvis.pineapple.cloud.StoredCloudSession
import com.jarvis.pineapple.db.JarvisDatabase
import com.jarvis.pineapple.model.AiModel
import com.jarvis.pineapple.model.DownloadStatus
import com.jarvis.pineapple.model.FormatProbe
import com.jarvis.pineapple.model.ModelCapabilityScheduler
import com.jarvis.pineapple.model.ModelDownloadManager
import com.jarvis.pineapple.model.ModelFormat
import com.jarvis.pineapple.model.ModelFormatDetector
import com.jarvis.pineapple.model.ModelInfo
import com.jarvis.pineapple.model.ModelScheduler
import com.jarvis.pineapple.model.ModelSource
import com.jarvis.pineapple.model.ModelState
import com.jarvis.pineapple.security.AuditLogStore
import com.jarvis.pineapple.system.HostRootManager
import com.jarvis.pineapple.vm.HavenVmManager
import com.jarvis.pineapple.vm.ProotManager
import com.jarvis.pineapple.vm.SnapshotResult
import com.jarvis.pineapple.vm.VmAuditLog
import com.jarvis.pineapple.vm.VmDistroManager
import com.jarvis.pineapple.vm.VmRuntime
import com.jarvis.pineapple.vm.VmSnapshotManager
import com.jarvis.pineapple.vm.VncClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** 40 轮共用的生产类压力执行器；不包含任何真机 PRoot、su、x11vnc 或云厂商验收声明。 */
internal class LoadSubsystemStress(private val root: File, private val round: Int) {

    fun runChat(profile: LoadProfile) {
        val conversationDir = directory("chat")
        val memoryDir = directory("chat-memory")
        val driver = memoryDriver()
        try {
            val database = JarvisDatabase(driver)
            val repository = ConversationRepository(conversationsDirOverride = conversationDir)
            val memory = MemorySystem(File(memoryDir, "memories.json"), File(memoryDir, "archive"), database)
            val model = StressModel(profile)
            val vm = StressVmBridge()
            val rootBridge = StressRootBridge()
            val scheduler = ModelScheduler(model)
            val engine = ChatEngine.createForTest(
                modelScheduler = scheduler,
                conversationRepository = repository,
                memorySystem = memory,
                capabilityScheduler = ModelCapabilityScheduler(scheduler),
                conversationEnhancer = ConversationEnhancer(TranslationEngine(apiBaseUrl = "")),
                vmCommandBridge = vm,
                rootCommandBridge = rootBridge,
                systemPromptProvider = { "压力测试人格" }
            )
            assertTrue(engine.newConversation("聊天压力-$round"))
            val conversationId = requireNotNull(repository.currentConversationId.value)
            var firstUser = ""

            repeat(profile.chatTurns) { index ->
                val user = "chat-$round-$index:" + "问".repeat(profile.payloadChars)
                if (index == 0) firstUser = user
                model.expectedToken = "chat-$round-$index:"
                model.mode = when (index % 4) {
                    1 -> StressModel.Mode.FAILURE
                    2 -> StressModel.Mode.CANCELLATION
                    else -> StressModel.Mode.SUCCESS
                }
                when (model.mode) {
                    StressModel.Mode.CANCELLATION -> runBlocking {
                        val stream = engine.sendUserMessage(user)
                        val job = launch(Dispatchers.Default) { stream.toList() }
                        withTimeout(10_000) {
                            while (engine.messages.value.lastOrNull()?.content?.contains("流") != true) yield()
                        }
                        job.cancelAndJoin()
                    }
                    else -> runBlocking { engine.sendUserMessage(user).toList() }
                }
                val terminal = engine.messages.value.last()
                assertFalse(terminal.isStreaming)
                assertFalse(engine.isGenerating.value)
                when (model.mode) {
                    StressModel.Mode.SUCCESS -> assertEquals(model.reply, terminal.content)
                    StressModel.Mode.FAILURE -> assertTrue(terminal.content.contains("模型请求失败"))
                    StressModel.Mode.CANCELLATION -> assertTrue(terminal.content.contains("模型请求已取消"))
                }
            }

            assertTrue(send(engine, "终端执行 echo chat-$round").contains("vm-output"))
            assertTrue(send(engine, "root 执行 id").contains("root-output"))
            assertTrue(send(engine, "终端执行 rm -rf /").contains("二次确认"))
            assertTrue(send(engine, "取消").contains("未执行任何命令"))
            assertTrue(vm.commands.none { it.second.contains("rm -rf") })
            assertTrue(send(engine, "root 执行 rm -rf /").contains("二次确认"))
            assertTrue(send(engine, "确认执行").contains("root-output"))
            assertTrue(rootBridge.commands.contains("rm -rf /"))

            val terminalMessages = engine.messages.value
            val reloadedRepository = ConversationRepository(conversationsDirOverride = conversationDir)
            assertTrue(reloadedRepository.switchConversation(conversationId))
            assertEquals(terminalMessages, reloadedRepository.getCurrentConversation()?.messages)
            val turns = memory.getConversationTurns(conversationId)
            assertTrue(turns.any { it.role == "USER" && it.content == firstUser })
            assertTrue(turns.size >= profile.chatTurns * 2)
            val reloadedMemory = MemorySystem(File(memoryDir, "memories.json"), File(memoryDir, "archive"), database)
            assertEquals(turns, reloadedMemory.getConversationTurns(conversationId))

            val exported = engine.exportAllConversations()
            val imported = ConversationRepository(conversationsDirOverride = directory("chat-import"))
            assertTrue(imported.importFromJson(exported))
            assertEquals(terminalMessages, imported.getCurrentConversation()?.messages)
            assertFalse(engine.importConversations("{broken"))
            assertEquals(exported, engine.exportAllConversations())
        } finally {
            driver.close()
        }
    }

    fun runBoundaries(profile: LoadProfile) {
        runVm(profile)
        runVnc(profile)
        runModels(profile)
        runCloud(profile)
        runAuditAndRoot(profile)
        runImportExportAndMemory(profile)
        if (round == 40) {
            val before = ResourceSample.capture()
            runMixedSoak(profile)
            val after = ResourceSample.capture()
            println(
                "LOAD_RESOURCE scope=build-host-jvm round=$round " +
                    "heapBefore=${before.heapBytes} heapAfter=${after.heapBytes} " +
                    "threadsBefore=${before.threads} threadsAfter=${after.threads} " +
                    "fdBefore=${before.fdCount} fdAfter=${after.fdCount}"
            )
            assertTrue("构建主机线程异常增长", after.threads <= before.threads + 64)
            if (before.fdCount >= 0 && after.fdCount >= 0) {
                assertTrue("构建主机文件描述符疑似泄漏", after.fdCount <= before.fdCount + 32)
            }
        }
    }

    private fun runVm(profile: LoadProfile) = runBlocking {
        val files = directory("vm-files")
        val proot = ProotManager(files, directory("vm-cache"), { arrayOf("arm64-v8a") }, { false })
        val base = proot.rootfsRoot().resolve("alpine_3_21")
        base.resolve("bin").mkdirs()
        base.resolve("bin/busybox").writeText("base-$round")
        val distro = VmDistroManager(directory("distro-cache"), proot)
        val instances = File(files, "vm_instances")
        val runtime = StressVmRuntime()
        val manager = HavenVmManager(proot, distro, runtime, instances)
        val first = requireNotNull(manager.createVm("first-$round", "alpine_3_21").vm)
        val second = requireNotNull(manager.createVm("second-$round", "alpine_3_21").vm)
        assertNotEquals(first.rootfsPath, second.rootfsPath)
        File(first.rootfsPath, "isolated").writeText("first")
        assertFalse(File(second.rootfsPath, "isolated").exists())
        assertFalse(File(base, "isolated").exists())
        assertEquals(-1, manager.executeCommandResult(first.id, "echo stopped")?.exitCode)
        assertFalse(manager.resumeVm(first.id))
        assertTrue(manager.startVm(first.id))
        assertTrue(manager.startVm(second.id))
        assertTrue(manager.startVm(first.id))

        val snapshots = snapshotManager(manager, first.id)
        assertTrue(snapshots.createSnapshot("running-rejected") is SnapshotResult.Failure)
        runtime.resetConcurrencySamples()
        val jobs = buildList {
            repeat(profile.vmCommandRepeats) { index ->
                add(async { manager.executeCommandResult(first.id, "work-first-a-$index") })
                add(async { manager.executeCommandResult(first.id, "work-first-b-$index") })
                add(async { manager.executeCommandResult(second.id, "work-second-$index") })
            }
        }
        assertTrue(jobs.awaitAll().all { it?.exitCode == 0 && it.output.startsWith("ok:") })
        assertEquals(1, runtime.maxConcurrentFor(first.rootfsPath))
        assertEquals(1, runtime.maxConcurrentFor(second.rootfsPath))
        assertTrue("不同 VM 应真实重叠执行测试命令", runtime.maxGlobalConcurrency.get() >= 2)

        assertTrue(manager.pauseVm(first.id))
        runtime.stopResult = false
        assertFalse(manager.stopVm(first.id))
        assertEquals(HavenVmManager.HavenVmState.PAUSED, manager.findVm(first.id)?.state)
        runtime.stopResult = true
        assertTrue(manager.stopVm(first.id))
        File(first.rootfsPath, "version").writeText("before")
        val snapshot = snapshots.createSnapshot("baseline-$round")
        assertTrue(snapshot is SnapshotResult.Success)
        val snapshotId = snapshots.snapshots.value.single().id
        File(first.rootfsPath, "version").writeText("after")
        assertTrue(snapshots.rollbackToSnapshot(snapshotId) is SnapshotResult.Success)
        assertEquals("before", File(first.rootfsPath, "version").readText())
        assertTrue(snapshots.deleteSnapshot(snapshotId) is SnapshotResult.Success)
        assertTrue(snapshots.rollbackToSnapshot(snapshotId) is SnapshotResult.Failure)

        assertTrue(manager.stopVm(second.id))
        val firstRootfs = File(first.rootfsPath)
        val secondRootfs = File(second.rootfsPath)
        assertTrue(manager.deleteVm(first.id))
        assertTrue(manager.deleteVm(second.id))
        assertFalse(firstRootfs.exists())
        assertFalse(secondRootfs.exists())
        assertNull(manager.findVm(first.id))
    }

    private fun runVnc(profile: LoadProfile) = runBlocking {
        ServerSocket(0).use { server ->
            val serverJob = launch(Dispatchers.IO) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    writeServerHandshake(input, output, profile.vncRects, 1)
                    readInitialClientMessages(input)
                    val bytes = ByteArrayOutputStream()
                    val frame = DataOutputStream(bytes)
                    frame.writeByte(0)
                    frame.writeByte(0)
                    frame.writeShort(profile.vncRects)
                    repeat(profile.vncRects) { x ->
                        frame.writeShort(x)
                        frame.writeShort(0)
                        frame.writeShort(1)
                        frame.writeShort(1)
                        frame.writeInt(0)
                        frame.writeByte(x * 3)
                        frame.writeByte(x * 3 + 1)
                        frame.writeByte(x * 3 + 2)
                        frame.writeByte(0)
                    }
                    frame.flush()
                    writeFragmented(output, bytes.toByteArray())
                }
            }
            val connected = kotlinx.coroutines.CompletableDeferred<Unit>()
            val frame = kotlinx.coroutines.CompletableDeferred<IntArray>()
            val client = VncClient(object : VncClient.Listener {
                override fun onConnected(width: Int, height: Int) {
                    assertEquals(profile.vncRects, width)
                    assertEquals(1, height)
                    connected.complete(Unit)
                }

                override fun onFrame(pixels: IntArray, width: Int, height: Int) {
                    if (!frame.isCompleted) frame.complete(pixels.copyOf())
                }

                override fun onDisconnected(reason: String) = Unit
            })
            val clientJob = launch(Dispatchers.IO) { client.run("127.0.0.1", server.localPort) }
            withTimeout(10_000) { connected.await() }
            val decoded = withTimeout(10_000) { frame.await() }
            client.disconnect()
            withTimeout(10_000) { clientJob.join() }
            withTimeout(10_000) { serverJob.join() }
            val expected = IntArray(profile.vncRects) { x ->
                val blue = (x * 3) and 0xff
                val green = (x * 3 + 1) and 0xff
                val red = (x * 3 + 2) and 0xff
                0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
            }
            assertArrayEquals(expected, decoded)
        }

        ServerSocket(0).use { server ->
            val serverJob = launch(Dispatchers.IO) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    writeServerHandshake(input, output, 1, 1)
                    readInitialClientMessages(input)
                    output.writeByte(0)
                    output.writeByte(0)
                    output.writeShort(1)
                    output.writeShort(1)
                    output.writeShort(0)
                    output.writeShort(1)
                    output.writeShort(1)
                    output.writeInt(0)
                    output.flush()
                }
            }
            val disconnected = kotlinx.coroutines.CompletableDeferred<String>()
            val client = VncClient(object : VncClient.Listener {
                override fun onConnected(width: Int, height: Int) = Unit
                override fun onFrame(pixels: IntArray, width: Int, height: Int) = Unit
                override fun onDisconnected(reason: String) {
                    disconnected.complete(reason)
                }
            })
            val clientJob = launch(Dispatchers.IO) { client.run("127.0.0.1", server.localPort) }
            val reason = withTimeout(10_000) { disconnected.await() }
            client.disconnect()
            withTimeout(10_000) { clientJob.join() }
            withTimeout(10_000) { serverJob.join() }
            assertTrue(reason, reason.contains("矩形越界"))
        }
        Unit
    }

    private fun runModels(profile: LoadProfile) = runBlocking {
        println("LOAD_STAGE round=$round stage=models.formats")
        val formatDir = directory("formats")
        val gguf = File(formatDir, "valid.gguf").apply { writeBytes(ggufBytes()) }
        val detected = ModelFormatDetector.probe(gguf)
        assertTrue(detected is FormatProbe.Detected && detected.format == ModelFormat.GGUF)
        assertTrue(ModelFormatDetector.probe(File(formatDir, "short.gguf").apply {
            writeBytes(ggufBytes().copyOf(10))
        }) is FormatProbe.Rejected)
        assertTrue(ModelFormatDetector.probe(File(formatDir, "random.bin").apply {
            writeBytes(ByteArray(profile.modelPayloadBytes) { (it * 17).toByte() })
        }) is FormatProbe.Rejected)
        val safeJson = """{"weight":{"dtype":"F32","shape":[2,2],"data_offsets":[0,16]}}"""
        val safeBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(safeJson.length.toLong()).array() +
            safeJson.toByteArray() + ByteArray(16)
        val safe = ModelFormatDetector.probe(File(formatDir, "valid.safetensors").apply { writeBytes(safeBytes) })
        assertTrue(safe is FormatProbe.Detected && safe.tensorCount == 1L)
        assertTrue(ModelFormatDetector.probe(File(formatDir, "truncated.safetensors").apply {
            writeBytes(safeBytes.copyOf(safeBytes.size - 1))
        }) is FormatProbe.Rejected)

        println("LOAD_STAGE round=$round stage=models.concurrent-download")
        val payload = ByteArray(profile.modelPayloadBytes) { (it * 31 + round).toByte() }
        val config = modelConfig(payload)
        val requests = AtomicInteger()
        HttpClient(MockEngine {
            requests.incrementAndGet()
            respond(payload, headers = headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        }).use { client ->
            val manager = ModelDownloadManager(directory("download-success"), client, listOf(config))
            val results = withTimeout(10_000) {
                listOf(async { manager.downloadModel("tiny") }, async { manager.downloadModel("tiny") }).awaitAll()
            }
            assertTrue(results.all { it })
            assertEquals(1, requests.get())
            assertEquals(DownloadStatus.COMPLETED, manager.getDownloadProgress("tiny")?.status)
            assertNotNull(manager.getModelFilePath("tiny"))
            assertTrue(manager.deleteModel("tiny"))
            assertNull(manager.getModelFilePath("tiny"))
        }

        println("LOAD_STAGE round=$round stage=models.mirror-fallback")
        HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("primary")) throw SocketTimeoutException("primary timeout")
            respond(payload, headers = headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        }).use { client ->
            val mirrorConfig = config.copy(
                downloadUrl = "https://models.test/primary",
                mirrorUrls = listOf("https://models.test/mirror")
            )
            val manager = ModelDownloadManager(directory("download-mirror"), client, listOf(mirrorConfig))
            assertTrue(manager.downloadModel("tiny"))
            assertEquals(DownloadStatus.COMPLETED, manager.getDownloadProgress("tiny")?.status)
        }

        println("LOAD_STAGE round=$round stage=models.resume")
        val resumeDir = directory("download-resume")
        val split = payload.size / 2
        File(resumeDir, "tiny.litertlm.part").writeBytes(payload.copyOf(split))
        HttpClient(MockEngine { request ->
            assertEquals("bytes=$split-", request.headers[HttpHeaders.Range])
            respond(
                payload.copyOfRange(split, payload.size),
                HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentLength, (payload.size - split).toString())
            )
        }).use { client ->
            val manager = ModelDownloadManager(resumeDir, client, listOf(config))
            assertTrue(manager.downloadModel("tiny"))
            assertArrayEquals(payload, File(resumeDir, "tiny.litertlm").readBytes())
        }

        println("LOAD_STAGE round=$round stage=models.range-error")
        HttpClient(MockEngine {
            respond("range unsupported", HttpStatusCode.RequestedRangeNotSatisfiable)
        }).use { client ->
            val manager = ModelDownloadManager(directory("download-range-error"), client, listOf(config))
            assertFalse(manager.downloadModel("tiny"))
            assertTrue(manager.getDownloadProgress("tiny")?.errorMessage.orEmpty().contains("416"))
            assertFalse(manager.isDownloading.value)
        }

        println("LOAD_STAGE round=$round stage=models.sha-error")
        HttpClient(MockEngine { respond(payload) }).use { client ->
            val wrong = config.copy(sha256 = "0".repeat(64))
            val manager = ModelDownloadManager(directory("download-sha"), client, listOf(wrong))
            assertFalse(manager.downloadModel("tiny"))
            assertEquals(DownloadStatus.FAILED, manager.getDownloadProgress("tiny")?.status)
            assertTrue(manager.getDownloadProgress("tiny")?.errorMessage.orEmpty().contains("SHA-256"))
        }

        println("LOAD_STAGE round=$round stage=models.storage-error")
        val blocked = File(root, "blocked-models-$round").apply { writeText("not a directory") }
        HttpClient(MockEngine { respond(payload) }).use { client ->
            val manager = ModelDownloadManager(blocked, client, listOf(config))
            assertFalse(manager.downloadModel("tiny"))
            assertEquals(DownloadStatus.FAILED, manager.getDownloadProgress("tiny")?.status)
            assertFalse(manager.isDownloading.value)
        }
    }

    private fun runCloud(profile: LoadProfile) = runBlocking {
        val store = StressSessionStore()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/login" -> respondJson("""{"success":true,"token":"load-token"}""")
                "/api/devices" -> {
                    assertEquals("Bearer load-token", request.headers[HttpHeaders.Authorization])
                    respondJson("""{"devices":[{"id":"peer-$round","name":"Peer","type":"phone","online":true}]}""")
                }
                "/api/devices/pair", "/api/sync/conversation", "/api/sync/memory",
                "/api/sync/settings", "/api/sync/reminders", "/api/commands" -> {
                    assertEquals("Bearer load-token", request.headers[HttpHeaders.Authorization])
                    if (request.url.encodedPath.endsWith("pair")) {
                        respondJson("""{"success":true,"message":"ok"}""")
                    } else {
                        respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val manager = CloudSyncManager(BASE_URL, engine, store)
        assertTrue(manager.login("load-user", "load-password"))
        val payload = "云".repeat(profile.payloadChars)
        repeat(profile.chatTurns) { index ->
            assertEquals(1, manager.discoverDevices()?.size)
            assertTrue(manager.syncConversationState("$index:$payload"))
            assertTrue(manager.syncMemory("encrypted-$index:$payload"))
            assertTrue(manager.syncSettings("{\"index\":$index}"))
            assertTrue(manager.syncReminders("[]"))
            assertTrue(manager.pairDevice("code-$index", "peer-$index"))
            assertTrue(manager.sendRemoteCommand("peer-$round", "load", mapOf("index" to index.toString())))
        }
        assertTrue(manager.syncStatus.value.lastSyncTime > 0)
        assertFalse(manager.syncStatus.value.isSyncing)
        assertNull(manager.lastError.value)
        manager.logout()
        assertFalse(manager.isLoggedIn())
        assertNull(store.saved)

        val unavailable = CloudSyncManager(
            BASE_URL,
            MockEngine { respond("down", HttpStatusCode.ServiceUnavailable) },
            StressSessionStore(StoredCloudSession("load-user", "token"))
        )
        assertFalse(unavailable.syncSettings("{}"))
        assertFalse(unavailable.syncStatus.value.isSyncing)
        assertTrue(unavailable.lastError.value.orEmpty().contains("HTTP 503"))

        val timeout = CloudSyncManager(BASE_URL, MockEngine { throw SocketTimeoutException("timeout") }, StressSessionStore())
        assertFalse(timeout.login("load-user", "password"))
        assertTrue(timeout.lastError.value.orEmpty().contains("超时"))
        val offline = CloudSyncManager(BASE_URL, MockEngine { throw IOException("offline") }, StressSessionStore())
        assertFalse(offline.login("load-user", "password"))
        assertTrue(offline.lastError.value.orEmpty().contains("网络连接失败"))
        val failedStore = StressSessionStore(saveResult = false)
        val rejected = CloudSyncManager(
            BASE_URL,
            MockEngine { respondJson("""{"success":true,"token":"not-persisted"}""") },
            failedStore
        )
        assertFalse(rejected.login("load-user", "password"))
        assertFalse(rejected.isLoggedIn())
        val disabled = CloudSyncManager("", MockEngine { error("must not request") }, StressSessionStore())
        assertFalse(disabled.login("load-user", "password"))
        assertTrue(disabled.lastError.value.orEmpty().contains("云端未配置"))
    }

    private fun runAuditAndRoot(profile: LoadProfile) {
        val dir = directory("audit")
        val file = File(dir, "audit.log")
        val store = AuditLogStore(file)
        repeat(profile.auditEntries) { index ->
            val secret = if (index % 50 == 0) " --token load-secret-$index\r\ninjected" else ""
            assertTrue(store.append("event=$index$secret"))
        }
        assertEquals(500, store.snapshot().size)
        assertEquals(500, file.readLines().size)
        assertTrue(store.snapshot().last().contains("event=${profile.auditEntries - 1}"))
        assertFalse(file.readText().contains("load-secret"))
        assertFalse(store.snapshot().any { it.contains('\r') || it.contains('\n') })

        val vmAudit = VmAuditLog(File(dir, "vm.log"))
        assertTrue(vmAudit.record("vm-$round", "root", "curl --token raw-secret", 7))
        assertFalse(vmAudit.recent().single().contains("raw-secret"))
        val failedVmAudit = VmAuditLog(File(dir, "vm-blocked").apply { mkdirs() })
        assertFalse(failedVmAudit.record("vm", "root", "id", 0))
        assertTrue(failedVmAudit.lastError.orEmpty().contains("写入失败"))

        var allowed = true
        val calls = AtomicInteger()
        val rootManager = HostRootManager(
            auditStore = AuditLogStore(File(dir, "root.log")),
            allowedProvider = { allowed },
            allowedUpdater = { allowed = it },
            suRunner = { command, _ ->
                calls.incrementAndGet()
                if (command == "id") RootRunResult("uid=0(root)", 0) else RootRunResult("real-output", 23)
            }
        )
        assertTrue(runBlocking { rootManager.check() }.granted)
        val result = runBlocking { rootManager.execute("service restart --api-key root-secret") }
        assertEquals(23, result.exitCode)
        assertFalse(rootManager.auditEntries().single().contains("root-secret"))
        rootManager.setAllowed(false)
        assertEquals(-1, runBlocking { rootManager.execute("id") }.exitCode)
        assertEquals(2, calls.get())

        val failedRoot = HostRootManager(
            auditStore = AuditLogStore(File(dir, "root-blocked").apply { mkdirs() }),
            allowedProvider = { true },
            allowedUpdater = {},
            suRunner = { _, _ -> RootRunResult("real-output", 19) }
        )
        val failedResult = runBlocking { failedRoot.execute("id") }
        assertEquals(19, failedResult.exitCode)
        assertTrue(failedResult.output.contains("警告"))
    }

    private fun runImportExportAndMemory(profile: LoadProfile) {
        val source = ConversationRepository(conversationsDirOverride = directory("import-source"))
        val original = requireNotNull(source.createConversation("source-$round"))
        val messages = listOf(
            ChatMessage(role = Role.USER, content = "原".repeat(profile.payloadChars)),
            ChatMessage(role = Role.ASSISTANT, content = "答".repeat(profile.payloadChars))
        )
        assertTrue(source.updateMessages(messages))
        val exported = source.exportAll()
        val dto = ConversationDto.fromConversation(requireNotNull(source.getCurrentConversation()))
        assertFalse(source.importFromJson(Json.encodeToString(listOf(dto, dto))))
        assertEquals(exported, source.exportAll())
        assertFalse(source.importFromJson("{bad-json"))
        assertEquals(exported, source.exportAll())
        val target = ConversationRepository(conversationsDirOverride = directory("import-target"))
        assertTrue(target.importFromJson(exported))
        assertEquals(messages, target.getCurrentConversation()?.messages)
        assertNotNull(target.exportConversation(original.id))
        assertTrue(target.setArchived(original.id, true))
        assertTrue(target.getCurrentConversation()?.archived == true)

        val rollbackDir = directory("import-rollback")
        val rollback = ConversationRepository(conversationsDirOverride = rollbackDir)
        requireNotNull(rollback.createConversation("baseline"))
        assertTrue(rollback.updateMessages(listOf(ChatMessage(role = Role.USER, content = "keep"))))
        val before = rollback.exportAll()
        val blockedId = "blocked_$round"
        File(rollbackDir, "$blockedId.json").mkdirs()
        File(rollbackDir, "$blockedId.json/child").writeText("prevent rename")
        val batch = listOf(
            ConversationDto("imported_$round", "new", emptyList(), 1),
            ConversationDto(blockedId, "blocked", emptyList(), 2)
        )
        assertFalse(rollback.importFromJson(Json.encodeToString(batch)))
        assertEquals(before, rollback.exportAll())
        assertFalse(File(rollbackDir, "imported_$round.json").exists())

        val memoryDir = directory("boundary-memory")
        val driver = memoryDriver()
        try {
            val memory = MemorySystem(File(memoryDir, "memories.json"), File(memoryDir, "archive"), JarvisDatabase(driver))
            val user = "完整用户原文：" + "记".repeat(profile.payloadChars)
            val assistant = "完整助手原文：" + "忆".repeat(profile.payloadChars)
            assertTrue(memory.recordConversationTurn("boundary-$round", user, assistant, "boundary-topic-$round"))
            assertEquals(user, memory.getConversationTurns("boundary-$round").single { it.role == "USER" }.content)
            assertTrue(File(memoryDir, "memories.json").isFile)
            assertTrue(File(memoryDir, "memories.json.bak").isFile)
            assertEquals(File(memoryDir, "memories.json").readText(), File(memoryDir, "memories.json.bak").readText())
            assertFalse(memory.recordConversationTurn("secret", "token = raw-secret", "no", "no"))
            assertTrue(memory.search("boundary-topic").isNotEmpty())
            val exportedMemory = memory.exportMemories()
            val importDriver = memoryDriver()
            try {
                val imported = MemorySystem(
                    File(directory("boundary-memory-import"), "memories.json"),
                    File(memoryDir, "import-archive"),
                    JarvisDatabase(importDriver)
                )
                assertTrue(imported.importMemories(exportedMemory))
                assertTrue(imported.memories.value.any { it.content == "boundary-topic-$round" })
            } finally {
                importDriver.close()
            }
            assertTrue(memory.forgetByTopic("boundary-topic-$round"))
            assertTrue(memory.getConversationTurns("boundary-$round").isEmpty())
            assertFalse(File(memoryDir, "memories.json").readText().contains("boundary-topic-$round"))
            assertTrue(memory.clearAll())
            assertTrue(memory.getConversationTurns().isEmpty())
        } finally {
            driver.close()
        }
    }

    /** 第 40 轮额外的混合 soak：会话、记忆、云请求、格式解析和审计并发运行。 */
    private fun runMixedSoak(profile: LoadProfile) = runBlocking {
        val soakRoot = directory("mixed-soak")
        val gguf = File(soakRoot, "shared.gguf").apply { writeBytes(ggufBytes()) }
        withTimeout(90_000) {
            (0 until profile.workers).map { worker ->
                async(Dispatchers.Default) {
                    val workerDir = File(soakRoot, "worker-$worker").apply { mkdirs() }
                    val repository = ConversationRepository(conversationsDirOverride = File(workerDir, "conversations"))
                    val audit = AuditLogStore(File(workerDir, "audit.log"))
                    val driver = memoryDriver()
                    try {
                        val memory = MemorySystem(
                            File(workerDir, "memory/memories.json"),
                            File(workerDir, "memory/archive"),
                            JarvisDatabase(driver)
                        )
                        val cloud = CloudSyncManager(
                            BASE_URL,
                            MockEngine { request ->
                                if (request.url.encodedPath == "/api/auth/login") {
                                    respondJson("""{"success":true,"token":"soak-$worker"}""")
                                } else {
                                    respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                                }
                            },
                            StressSessionStore()
                        )
                        assertTrue(cloud.login("soak-$worker", "password"))
                        repeat(profile.createsPerWorker) { index ->
                            val command = LoadProfile.COMMANDS[(worker + index) % LoadProfile.COMMANDS.size]
                            assertNotEquals("UNKNOWN", com.jarvis.pineapple.chat.AssistantCommandParser.parse(command).action)
                            assertTrue(ModelFormatDetector.probe(gguf) is FormatProbe.Detected)
                            val conversation = requireNotNull(repository.createConversation("soak-$worker-$index"))
                            val user = "soak-user-$worker-$index"
                            val assistant = "soak-assistant-$worker-$index"
                            assertTrue(repository.updateMessages(listOf(
                                ChatMessage(role = Role.USER, content = user),
                                ChatMessage(role = Role.ASSISTANT, content = assistant)
                            )))
                            assertTrue(memory.recordConversationTurn(conversation.id, user, assistant, "soak-$worker-$index"))
                            assertTrue(audit.append("worker=$worker index=$index"))
                            assertTrue(cloud.syncSettings("{\"worker\":$worker,\"index\":$index}"))
                        }
                        assertEquals(profile.createsPerWorker, repository.conversations.value.size)
                        assertEquals(profile.createsPerWorker * 2, memory.getConversationTurns().size)
                        assertEquals(profile.createsPerWorker, audit.snapshot().size)
                        assertFalse(cloud.syncStatus.value.isSyncing)
                    } finally {
                        driver.close()
                    }
                }
            }.awaitAll()
        }
        Unit
    }

    private fun directory(name: String): File = File(root, "$name-$round").apply {
        check(mkdirs() || isDirectory) { "无法创建压测目录：$absolutePath" }
    }

    private fun memoryDriver(): JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        JarvisDatabase.Schema.create(it)
    }

    private fun send(engine: ChatEngine, content: String): String = runBlocking {
        engine.sendUserMessage(content).toList().joinToString("")
    }

    private fun snapshotManager(manager: HavenVmManager, vmId: String): VmSnapshotManager {
        val vm = requireNotNull(manager.findVm(vmId))
        return VmSnapshotManager(
            vmId = vmId,
            rootfsPath = vm.rootfsPath,
            snapshotsDir = manager.snapshotsDir(vmId),
            stateProvider = { manager.findVm(vmId)?.state },
            operationLock = manager.operationLockFor(vmId),
            prepareStopped = { manager.prepareStoppedVm(vmId) }
        )
    }

    private fun modelConfig(payload: ByteArray) = ModelDownloadManager.ModelConfig(
        id = "tiny",
        name = "Tiny load model",
        fileName = "tiny.litertlm",
        downloadUrl = "https://models.test/tiny",
        sha256 = sha256(payload),
        sizeBytes = payload.size.toLong()
    )

    private class StressModel(profile: LoadProfile) : AiModel {
        enum class Mode { SUCCESS, FAILURE, CANCELLATION }

        override val info = ModelInfo("load-fake", "Load fake", ModelSource.LOCAL_USER)
        override var state: ModelState = ModelState.READY
        val reply: String = "流".repeat(profile.payloadChars)
        private val chunks = profile.overlayChunks.coerceAtLeast(1)
        @Volatile var expectedToken: String = ""
        @Volatile var mode: Mode = Mode.SUCCESS

        override suspend fun load() {
            state = ModelState.READY
        }

        override suspend fun unload() {
            state = ModelState.UNLOADED
        }

        override suspend fun generate(prompt: String, systemPrompt: String?): String = reply

        override fun stream(prompt: String, systemPrompt: String?): Flow<String> = flow {
            check(prompt.contains(expectedToken)) { "完整用户输入未进入模型上下文" }
            val size = (reply.length / chunks).coerceAtLeast(1)
            reply.chunked(size).forEach { emit(it) }
            when (mode) {
                Mode.SUCCESS -> Unit
                Mode.FAILURE -> throw IllegalStateException("injected-stream-$expectedToken")
                Mode.CANCELLATION -> awaitCancellation()
            }
        }
    }

    private class StressVmBridge : VmCommandBridge {
        val commands = mutableListOf<Pair<String, String>>()
        override suspend fun listVms(): List<VmSummary> = listOf(VmSummary("vm-load", "Load VM", true))
        override suspend fun start(vmId: String): Boolean = true
        override suspend fun run(vmId: String, command: String): VmRunResult {
            commands += vmId to command
            return VmRunResult("vm-output:$command", 0)
        }
        override suspend fun openDesktop(vmId: String): VmDesktopActionResult = VmDesktopActionResult(true, "desktop")
        override suspend fun openBrowser(vmId: String): VmDesktopActionResult = VmDesktopActionResult(true, "browser")
    }

    private class StressRootBridge : RootCommandBridge {
        val commands = mutableListOf<String>()
        override suspend fun status(): RootAccessStatus = RootAccessStatus(true, true, "fake root bridge")
        override suspend fun run(command: String): RootRunResult {
            commands += command
            return RootRunResult("root-output:$command", 0)
        }
    }

    private class StressVmRuntime : VmRuntime {
        @Volatile var stopResult = true
        val maxGlobalConcurrency = AtomicInteger()
        private val global = AtomicInteger()
        private val local = ConcurrentHashMap<String, AtomicInteger>()
        private val maximum = ConcurrentHashMap<String, AtomicInteger>()

        override fun isProotInstalled(): Boolean = true
        override suspend fun executeCommand(config: ProotManager.ProotConfig, command: String): ProotManager.CommandResult {
            val globalNow = global.incrementAndGet()
            maxGlobalConcurrency.updateAndGet { maxOf(it, globalNow) }
            val localNow = local.computeIfAbsent(config.rootfsPath) { AtomicInteger() }.incrementAndGet()
            maximum.computeIfAbsent(config.rootfsPath) { AtomicInteger() }.updateAndGet { maxOf(it, localNow) }
            return try {
                if (command.startsWith("work")) delay(2)
                if (command == "echo JARVIS_VM_OK") {
                    ProotManager.CommandResult(command, "JARVIS_VM_OK", 0, 0)
                } else {
                    ProotManager.CommandResult(command, "ok:$command", 0, 1)
                }
            } finally {
                local[config.rootfsPath]?.decrementAndGet()
                global.decrementAndGet()
            }
        }
        override suspend fun startSession(config: ProotManager.ProotConfig): Boolean = true
        override suspend fun stopSession(config: ProotManager.ProotConfig): Boolean = stopResult
        override suspend fun pauseSession(config: ProotManager.ProotConfig): Boolean = true
        override suspend fun resumeSession(config: ProotManager.ProotConfig): Boolean = true
        override suspend fun installPackage(config: ProotManager.ProotConfig, packageManager: String, packageName: String): Boolean = true
        override suspend fun uninstallPackage(config: ProotManager.ProotConfig, packageManager: String, packageName: String): Boolean = true
        override suspend fun updatePackages(config: ProotManager.ProotConfig, packageManager: String): Boolean = true
        override suspend fun listInstalledPackages(config: ProotManager.ProotConfig, packageManager: String): ProotManager.CommandResult =
            ProotManager.CommandResult("list", "base-files\nbusybox", 0, 0)
        override suspend fun stopBackground(key: String): Boolean = true
        override suspend fun launchBackground(config: ProotManager.ProotConfig, key: String, command: String): Boolean = true
        override fun copyRootfs(source: File, target: File) {
            check(source.copyRecursively(target, overwrite = false)) { "copy rootfs failed" }
        }
        fun maxConcurrentFor(rootfs: String): Int = maximum[rootfs]?.get() ?: 0
        fun resetConcurrencySamples() {
            maxGlobalConcurrency.set(0)
            maximum.clear()
        }
    }

    private class StressSessionStore(
        initial: StoredCloudSession? = null,
        private val saveResult: Boolean = true
    ) : CloudSessionStore {
        var saved: StoredCloudSession? = initial
        override var lastError: String? = null
        override fun load(): StoredCloudSession? = saved
        override fun save(account: String, token: String): Boolean {
            if (saveResult) {
                saved = StoredCloudSession(account, token)
                lastError = null
            } else {
                lastError = "安全保存失败"
            }
            return saveResult
        }
        override fun clear(): Boolean {
            saved = null
            lastError = null
            return true
        }
    }

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        body,
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json")
    )

    private data class ResourceSample(val heapBytes: Long, val threads: Int, val fdCount: Int) {
        companion object {
            fun capture(): ResourceSample {
                val runtime = Runtime.getRuntime()
                return ResourceSample(
                    heapBytes = runtime.totalMemory() - runtime.freeMemory(),
                    threads = Thread.getAllStackTraces().size,
                    fdCount = File("/proc/self/fd").list()?.size ?: -1
                )
            }
        }
    }

    private companion object {
        const val BASE_URL = "https://cloud.load.test"

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        fun ggufBytes(): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .put("GGUF".toByteArray(Charsets.US_ASCII))
            .putInt(3)
            .putLong(2)
            .putLong(1)
            .array()

        fun writeServerHandshake(input: DataInputStream, output: DataOutputStream, width: Int, height: Int) {
            writeFragmented(output, "RFB 003.008\n".toByteArray(Charsets.US_ASCII))
            readExactly(input, 12)
            writeFragmented(output, byteArrayOf(1, 1))
            readExactly(input, 1)
            writeFragmented(output, byteArrayOf(0, 0, 0, 0))
            readExactly(input, 1)
            val init = ByteArrayOutputStream()
            val data = DataOutputStream(init)
            data.writeShort(width)
            data.writeShort(height)
            data.write(byteArrayOf(32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0))
            data.writeInt(0)
            data.flush()
            writeFragmented(output, init.toByteArray())
        }

        fun readInitialClientMessages(input: DataInputStream) {
            readExactly(input, 20)
            readExactly(input, 8)
            readExactly(input, 10)
        }

        fun writeFragmented(output: DataOutputStream, bytes: ByteArray) {
            bytes.asList().chunked(3).forEach { chunk ->
                output.write(chunk.toByteArray())
                output.flush()
            }
        }

        fun readExactly(input: InputStream, count: Int) {
            val buffer = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(buffer, offset, count - offset)
                if (read < 0) throw IOException("连接提前关闭")
                offset += read
            }
        }
    }
}

package com.jarvis.pineapple.autonomous

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.jarvis.pineapple.db.JarvisDatabase
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 使用真实 JDBC SQLite 与文件系统验证 REQ-11，不以 StateFlow 代替持久化结果。 */
class MemorySystemTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: JarvisDatabase

    private val memoryDir: File
        get() = File(dir, "memory")
    private val memoryFile: File
        get() = File(memoryDir, "memories.json")
    private val backupFile: File
        get() = File(memoryDir, "memories.json.bak")
    private val archiveDir: File
        get() = File(memoryDir, "archive")
    private val rawDir: File
        get() = File(memoryDir, "raw")

    @Before
    fun setUp() {
        dir = tmp.newFolder("memory-test")
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        JarvisDatabase.Schema.create(driver)
        database = JarvisDatabase(driver)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun newSystem(): MemorySystem = MemorySystem(memoryFile, archiveDir, database)

    @Test
    fun rememberFactPersistsToSqliteAndBothBackups() {
        val system = newSystem()
        assertTrue(system.rememberFact("用户住在北京", importance = 8))

        assertTrue(memoryFile.isFile)
        assertTrue(backupFile.isFile)
        assertEquals(memoryFile.readText(), backupFile.readText())
        assertEquals(1, database.jarvisQueries.selectAllMemoryEntries().executeAsList().size)

        val facts = newSystem().getByType(MemoryType.KEY_FACT)
        assertEquals(1, facts.size)
        assertEquals("用户住在北京", facts.single().content)
        assertEquals(8, facts.single().importance)
    }

    @Test
    fun conversationTurnKeepsCompleteTextAndWritableJsonl() {
        val user = "第一行\n第二行 \"引号\" \\ 反斜杠\n" + "长".repeat(3_000)
        val assistant = "回答第一行\n回答第二行：雪花 ❄\n" + "答".repeat(3_000)
        val timestamp = 1_700_000_000_000L
        val system = newSystem()

        assertTrue(system.recordConversationTurn("conversation-full", user, assistant, "完整摘要", timestamp))
        val turns = system.getConversationTurns("conversation-full")
        assertEquals(listOf("USER", "ASSISTANT"), turns.map { it.role })
        assertEquals(user, turns[0].content)
        assertEquals(assistant, turns[1].content)

        val raw = rawDir.listFiles().orEmpty().single { it.name.endsWith(".jsonl") }
        val lines = raw.readLines()
        assertEquals("原文中的换行必须转义在一个 JSONL 记录内", 1, lines.size)
        val record = Json.parseToJsonElement(lines.single()).jsonObject
        assertEquals(user, record.getValue("userText").jsonPrimitive.content)
        assertEquals(assistant, record.getValue("assistantText").jsonPrimitive.content)
        assertEquals("conversation-full", record.getValue("conversationId").jsonPrimitive.content)
        assertEquals(timestamp, record.getValue("timestamp").jsonPrimitive.long)

        val reloaded = newSystem().getConversationTurns("conversation-full")
        assertEquals(turns, reloaded)
    }

    @Test
    fun importMemoriesValidPersistsAndInvalidInputDoesNotChangeState() {
        val system = newSystem()
        val json = """
            [{"id":"m1","type":"PREFERENCE","content":"深色主题","timestamp":100,"importance":6,"metadata":{}}]
        """.trimIndent()
        assertTrue(system.importMemories(json))
        assertEquals("深色主题", newSystem().getByType(MemoryType.PREFERENCE).single().content)

        val before = system.memories.value
        assertFalse(system.importMemories("{not valid json"))
        assertEquals(before, system.memories.value)
        assertTrue(system.lastError.value?.contains("导入失败") == true)
    }

    @Test
    fun sensitiveInputIsRejectedBeforeAnyStoreIsWritten() {
        val system = newSystem()
        val secrets = listOf(
            "我的密码是：CorrectHorseBatteryStaple",
            "API key: abcdef123456",
            "token = abcdef123456",
            "Authorization Bearer abcdef123456",
            "eyJabcdefgh.eyJabcdefgh.abcdefgh",
            "-----BEGIN PRIVATE KEY-----\nabcdef\n-----END PRIVATE KEY-----"
        )

        secrets.forEachIndexed { index, secret ->
            assertFalse(secret, system.recordConversationTurn("secret-$index", secret, "不应保存", "敏感摘要-$index"))
            assertTrue(system.lastError.value?.contains("敏感信息") == true)
        }

        assertTrue(system.memories.value.isEmpty())
        assertTrue(system.getConversationTurns().isEmpty())
        assertTrue(database.jarvisQueries.selectAllMemoryEntries().executeAsList().isEmpty())
        assertTrue(database.jarvisQueries.selectAllMemoryTurns().executeAsList().isEmpty())
        assertFalse(memoryFile.exists())
        assertFalse(backupFile.exists())
        assertFalse(rawDir.exists())
    }

    @Test
    fun legacyMigrationFailureRollsBackSqliteAndOriginalJson() {
        val legacy = """
            [{"id":"legacy-1","type":"KEY_FACT","content":"旧版事实","timestamp":100,"importance":8,"metadata":{}}]
        """.trimIndent()
        memoryDir.mkdirs()
        memoryFile.writeText(legacy)
        val original = memoryFile.readBytes()
        assertTrue(backupFile.mkdir())

        val system = newSystem()

        assertTrue(system.memories.value.isEmpty())
        assertTrue(system.lastError.value?.contains("保存失败") == true)
        assertTrue(database.jarvisQueries.selectAllMemoryEntries().executeAsList().isEmpty())
        assertTrue(original.contentEquals(memoryFile.readBytes()))
        assertTrue(backupFile.isDirectory)
    }

    @Test
    fun corruptPrimaryRecoversFromBackupWithVisibleWarning() {
        val legacy = """
            [{"id":"backup-1","type":"KEY_FACT","content":"可恢复事实","timestamp":100,"importance":8,"metadata":{}}]
        """.trimIndent()
        memoryDir.mkdirs()
        memoryFile.writeText("{broken")
        backupFile.writeText(legacy)

        val system = newSystem()

        assertEquals("可恢复事实", system.memories.value.single().content)
        assertTrue(system.lastError.value?.contains("恢复") == true)
        assertEquals(1, database.jarvisQueries.selectAllMemoryEntries().executeAsList().size)
        assertTrue(memoryFile.readText().contains("可恢复事实"))
        assertTrue(backupFile.readText().contains("可恢复事实"))
    }

    @Test
    fun backupFailureRollsBackPrimaryDatabaseAndState() {
        val system = newSystem()
        assertTrue(system.rememberFact("原事实"))
        val before = system.memories.value
        val original = memoryFile.readBytes()
        assertTrue(backupFile.delete())
        assertTrue(backupFile.mkdir())

        assertFalse(system.rememberFact("不能部分保存的新事实"))

        assertEquals(before, system.memories.value)
        assertTrue(system.lastError.value?.contains("保存失败") == true)
        assertTrue(original.contentEquals(memoryFile.readBytes()))
        val stored = database.jarvisQueries.selectAllMemoryEntries().executeAsList()
        assertEquals(listOf("原事实"), stored.map { it.content })
    }

    @Test
    fun forgetByIdRemovesSqliteRawArchiveAndBothBackups() {
        val system = newSystem()
        assertTrue(system.recordConversationTurn("delete-conversation", "delete-user", "delete-assistant", "delete-summary", 100))
        assertTrue(system.recordConversationTurn("keep-conversation", "keep-user", "keep-assistant", "keep-summary", 200))
        writeArchiveSnapshot(system)
        val target = system.memories.value.single { it.content == "delete-summary" }

        assertTrue(system.forget(target.id))

        assertTrue(system.getConversationTurns("delete-conversation").isEmpty())
        assertEquals(2, system.getConversationTurns("keep-conversation").size)
        assertStorageAbsent("delete-")
        assertTrue(File(archiveDir, "archive_test.json").readText().contains("keep-summary"))
        assertTrue(rawDir.listFiles().orEmpty().any { it.readText().contains("keep-user") })
        assertTrue(newSystem().getConversationTurns("delete-conversation").isEmpty())
    }

    @Test
    fun forgetByTypeAlsoPurgesArchivedEntriesAndRawTurns() {
        val system = newSystem()
        assertTrue(system.recordConversationTurn("type-delete", "type-delete-user", "type-delete-assistant", "type-delete-summary"))
        assertTrue(system.rememberPreference("type-keep"))
        writeArchiveSnapshot(system)

        assertTrue(system.forgetByType(MemoryType.CONVERSATION_RECORD))

        assertStorageAbsent("type-delete")
        assertEquals(listOf("type-keep"), system.memories.value.map { it.content })
        assertTrue(File(archiveDir, "archive_test.json").readText().contains("type-keep"))
        assertTrue(newSystem().getConversationTurns().isEmpty())
    }

    @Test
    fun forgetByTopicMatchesOriginalTextAndConversationId() {
        val system = newSystem()
        assertTrue(system.recordConversationTurn("topic-delete", "普通原文", "普通回答", "普通摘要"))
        assertTrue(system.rememberFact("topic-keep"))
        writeArchiveSnapshot(system)

        assertTrue(system.forgetByTopic("TOPIC-DELETE"))

        assertTrue(system.getConversationTurns("topic-delete").isEmpty())
        assertTrue(system.memories.value.none { it.metadata.containsValue("topic-delete") })
        assertTrue(database.jarvisQueries.selectAllMemoryTurns().executeAsList().isEmpty())
        assertFalse(File(archiveDir, "archive_test.json").readText().contains("topic-delete"))
        assertTrue(File(archiveDir, "archive_test.json").readText().contains("topic-keep"))
    }

    @Test
    fun clearAllPurgesLiveArchivedRawAndQuarantinedCopies() {
        val system = newSystem()
        assertTrue(system.recordConversationTurn("clear-delete", "clear-delete-user", "clear-delete-assistant", "clear-delete-summary"))
        writeArchiveSnapshot(system)
        memoryDir.mkdirs()
        File(memoryDir, "memories.json.corrupt-old").writeText("clear-delete-corrupt")

        assertTrue(system.clearAll())

        assertTrue(system.memories.value.isEmpty())
        assertTrue(system.getConversationTurns().isEmpty())
        assertTrue(database.jarvisQueries.selectAllMemoryEntries().executeAsList().isEmpty())
        assertTrue(database.jarvisQueries.selectAllMemoryTurns().executeAsList().isEmpty())
        assertStorageAbsent("clear-delete")
        assertTrue(newSystem().memories.value.isEmpty())
    }

    @Test
    fun unreadableArchiveBlocksDeletionInsteadOfReportingFalseSuccess() {
        val system = newSystem()
        assertTrue(system.rememberFact("archive-delete-target"))
        archiveDir.mkdirs()
        File(archiveDir, "archive_broken.json").writeText("{broken")

        assertFalse(system.forgetByTopic("archive-delete-target"))

        assertEquals("archive-delete-target", system.memories.value.single().content)
        assertTrue(system.lastError.value?.contains("删除失败") == true)
        assertEquals(1, database.jarvisQueries.selectAllMemoryEntries().executeAsList().size)
    }

    private fun writeArchiveSnapshot(system: MemorySystem) {
        archiveDir.mkdirs()
        File(archiveDir, "archive_test.json").writeText(system.exportMemories())
    }

    private fun assertStorageAbsent(needle: String) {
        assertTrue(database.jarvisQueries.selectAllMemoryEntries().executeAsList().none {
            needle in it.content || needle in it.metadata
        })
        assertTrue(database.jarvisQueries.selectAllMemoryTurns().executeAsList().none {
            needle in it.content || needle in it.conversation_id
        })
        memoryDir.walkTopDown().filter { it.isFile }.forEach { file ->
            assertFalse("${file.name} 仍残留 $needle", file.readText().contains(needle, ignoreCase = true))
        }
    }
}

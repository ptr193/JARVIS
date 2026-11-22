package com.jarvis.pineapple.autonomous

import android.content.Context
import com.jarvis.pineapple.db.DatabaseFactory
import com.jarvis.pineapple.db.JarvisDatabase
import com.jarvis.pineapple.db.Memory_entries
import com.jarvis.pineapple.db.Memory_turns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 记忆条目类型。 */
enum class MemoryType {
    CONVERSATION_RECORD, KEY_FACT, PREFERENCE, TASK_RECORD, USER_PROFILE
}

/** 单条结构化记忆。 */
data class MemoryEntry(
    val id: String = generateId(),
    val type: MemoryType,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val importance: Int = 5,
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        private val counter = AtomicInteger(0)
        fun generateId(): String = "mem_${System.nanoTime()}_${counter.getAndIncrement()}"
    }
}

/** SQLite 与按日 JSONL 共同保存的一条完整对话原文。 */
data class MemoryTurn(
    val id: String,
    val memoryId: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val timestamp: Long
)

/**
 * 本地长期记忆系统（REQ-11）。
 *
 * SQLite 是结构化记忆与原文索引的主存储；memories.json/.bak 是可恢复备份，
 * conversation-yyyy-MM-dd.jsonl 保存每次对话的完整用户/助手原文。
 */
class MemorySystem internal constructor(
    private val memoryFile: File,
    private val archiveDir: File,
    private val database: JarvisDatabase
) {

    constructor(context: Context, database: JarvisDatabase) : this(
        File(context.filesDir, "memory/memories.json"),
        File(context.filesDir, "memory/archive"),
        database
    )

    constructor(context: Context) : this(context, DatabaseFactory.create(context))

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val rawJson = Json { ignoreUnknownKeys = true }
    private val queries = database.jarvisQueries
    private val lock = Any()

    private val _memories = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val memories: StateFlow<List<MemoryEntry>> = _memories.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    private var allTurns: List<MemoryTurn> = emptyList()

    private val backupFile: File
        get() = File(memoryFile.parentFile, "${memoryFile.name}.bak")
    private val rawDir: File
        get() = File(memoryFile.parentFile, "raw")

    init {
        cleanupTemporaryFiles()
        loadMemories()
    }

    fun rememberFact(content: String, importance: Int = 7, metadata: Map<String, String> = emptyMap()): Boolean =
        addEntries(listOf(MemoryEntry(type = MemoryType.KEY_FACT, content = content, importance = importance, metadata = metadata)))

    fun rememberPreference(content: String, importance: Int = 6): Boolean =
        addEntries(listOf(MemoryEntry(type = MemoryType.PREFERENCE, content = content, importance = importance)))

    /** 兼容旧调用：只保存结构化摘要；真实聊天入口应使用 [recordConversationTurn]。 */
    fun recordConversation(summary: String, importance: Int = 3): Boolean =
        addEntries(listOf(MemoryEntry(type = MemoryType.CONVERSATION_RECORD, content = summary, importance = importance)))

    /** 每次对话结束增量保存结构化摘要与两条未截断原文。 */
    fun recordConversationTurn(
        conversationId: String,
        userText: String,
        assistantText: String,
        summary: String,
        timestamp: Long = System.currentTimeMillis()
    ): Boolean {
        if (conversationId.isBlank()) {
            _lastError.value = "记忆保存失败：会话 ID 不能为空"
            return false
        }
        val entry = MemoryEntry(
            type = MemoryType.CONVERSATION_RECORD,
            content = summary,
            timestamp = timestamp,
            importance = 3,
            metadata = mapOf("conversationId" to conversationId)
        )
        val turns = listOf(
            MemoryTurn("${entry.id}_user", entry.id, conversationId, "USER", userText, timestamp),
            MemoryTurn("${entry.id}_assistant", entry.id, conversationId, "ASSISTANT", assistantText, timestamp)
        )
        return addEntries(listOf(entry), turns)
    }

    fun recordTask(taskSummary: String, importance: Int = 5): Boolean =
        addEntries(listOf(MemoryEntry(type = MemoryType.TASK_RECORD, content = taskSummary, importance = importance)))

    fun updateUserProfile(content: String, importance: Int = 8): Boolean =
        addEntries(listOf(MemoryEntry(type = MemoryType.USER_PROFILE, content = content, importance = importance)))

    private fun addEntries(
        entries: List<MemoryEntry>,
        newTurns: List<MemoryTurn> = emptyList()
    ): Boolean = synchronized(lock) {
        if (!isSafeToStore(entries, newTurns)) {
            _lastError.value = "检测到敏感信息（密码、密钥或凭据），未写入记忆、原文或备份"
            return false
        }
        val current = _memories.value.toMutableList()
        val replacedIds = mutableSetOf<String>()
        entries.forEach { entry ->
            val existingIdx = current.indexOfFirst {
                it.id == entry.id || (it.type == entry.type && it.content == entry.content)
            }
            if (existingIdx >= 0) {
                replacedIds += current[existingIdx].id
                current[existingIdx] = entry
            } else {
                current.add(entry)
            }
        }

        var archiveFiles = emptyMap<File, String>()
        if (current.size > maxMemories) {
            val archived = prepareArchive(current)
            archiveFiles = archived.files
        }
        val sorted = current.sortedByDescending { it.timestamp }
        val replacementTurnIds = newTurns.mapTo(mutableSetOf()) { it.id }
        val updatedTurns = allTurns.filterNot {
            it.memoryId in replacedIds || it.id in replacementTurnIds
        } + newTurns
        val operation = if (archiveFiles.isEmpty()) "记忆保存失败" else "记忆归档失败"
        commitState(
            newEntries = sorted,
            newTurns = updatedTurns,
            extraFiles = archiveFiles,
            rebuildRaw = updatedTurns != allTurns,
            operation = operation
        )
    }

    fun search(query: String, limit: Int = 10): List<MemoryEntry> {
        val keywords = query.lowercase().split(" ", "，", "。", "？", "！", "、")
            .filter { it.length >= 2 }
        return _memories.value
            .map { entry ->
                val score = keywords.count { kw -> entry.content.lowercase().contains(kw) } +
                    (entry.importance / 10f)
                entry to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun getByType(type: MemoryType): List<MemoryEntry> = _memories.value.filter { it.type == type }

    fun getRecent(limit: Int = 20): List<MemoryEntry> =
        _memories.value.sortedByDescending { it.timestamp }.take(limit)

    fun getImportant(threshold: Int = 7): List<MemoryEntry> =
        _memories.value.filter { it.importance >= threshold }

    fun getConversationTurns(conversationId: String? = null): List<MemoryTurn> =
        allTurns.filter { conversationId == null || it.conversationId == conversationId }
            .sortedWith(compareBy<MemoryTurn> { it.timestamp }.thenBy { if (it.role == "USER") 0 else 1 }.thenBy { it.id })

    fun buildMemoryContext(currentQuery: String): String {
        val relevant = search(currentQuery, limit = 5)
        if (relevant.isEmpty()) return ""
        return buildString {
            appendLine("【历史记忆参考】")
            relevant.forEach { entry ->
                val typeLabel = when (entry.type) {
                    MemoryType.KEY_FACT -> "事实"
                    MemoryType.PREFERENCE -> "偏好"
                    MemoryType.CONVERSATION_RECORD -> "对话"
                    MemoryType.TASK_RECORD -> "任务"
                    MemoryType.USER_PROFILE -> "画像"
                }
                appendLine("- [$typeLabel] ${entry.content}")
            }
        }
    }

    fun forget(memoryId: String): Boolean = synchronized(lock) {
        removeMemories(setOf(memoryId), "记忆删除失败")
    }

    fun forgetByType(type: MemoryType): Boolean = synchronized(lock) {
        val archives = try {
            readArchiveEntries()
        } catch (e: Exception) {
            _lastError.value = "记忆删除失败：${e.message}"
            return false
        }
        val ids = (_memories.value + archives.values.flatten())
            .filter { it.type == type }
            .mapTo(mutableSetOf()) { it.id }
        removeMemories(ids, "按类型删除失败")
    }

    /** 按结构化内容、原文、metadata 或对话 ID 永久删除匹配记忆。 */
    fun forgetByTopic(topic: String): Boolean = synchronized(lock) {
        val needle = topic.trim()
        if (needle.isEmpty()) {
            _lastError.value = "按主题删除失败：主题或对话 ID 不能为空"
            return false
        }
        val archives = try {
            readArchiveEntries()
        } catch (e: Exception) {
            _lastError.value = "按主题删除失败：${e.message}"
            return false
        }
        fun MemoryEntry.matches(): Boolean =
            content.contains(needle, ignoreCase = true) ||
                metadata.any { (key, value) ->
                    key.contains(needle, ignoreCase = true) || value.contains(needle, ignoreCase = true)
                }
        val ids = (_memories.value + archives.values.flatten())
            .filter { it.matches() }
            .mapTo(mutableSetOf()) { it.id }
        ids += allTurns.filter {
            it.conversationId.contains(needle, ignoreCase = true) ||
                it.content.contains(needle, ignoreCase = true)
        }.map { it.memoryId }
        removeMemories(ids, "按主题删除失败")
    }

    private fun removeMemories(ids: Set<String>, operation: String): Boolean {
        val archives = try {
            readArchiveEntries()
        } catch (e: Exception) {
            _lastError.value = "$operation：${e.message}"
            return false
        }
        if (ids.isEmpty()) {
            _lastError.value = null
            return true
        }
        val archiveFiles = archives.mapValues { (_, entries) ->
            encodeEntries(entries.filterNot { it.id in ids })
        }.toMutableMap()
        archiveFiles += obsoleteBackupFiles()
        val updated = _memories.value.filterNot { it.id in ids }
        val updatedTurns = allTurns.filterNot { it.memoryId in ids }
        return commitState(
            newEntries = updated,
            newTurns = updatedTurns,
            extraFiles = archiveFiles,
            rebuildRaw = updatedTurns != allTurns,
            operation = operation
        )
    }

    fun clearAll(): Boolean = synchronized(lock) {
        val files = mutableMapOf<File, String>()
        archiveDir.listFiles { file -> file.isFile && file.name.startsWith("archive_") && file.name.endsWith(".json") }
            ?.forEach { files[it] = "[]" }
        files += obsoleteBackupFiles()
        commitState(
            newEntries = emptyList(),
            newTurns = emptyList(),
            extraFiles = files,
            rebuildRaw = true,
            clearDatabase = true,
            operation = "记忆清空失败"
        )
    }

    fun exportMemories(): String = encodeEntries(_memories.value)

    fun importMemories(jsonStr: String): Boolean {
        val entries = try {
            json.decodeFromString<List<MemoryEntryDto>>(jsonStr).map { it.toEntry() }
        } catch (e: Exception) {
            _lastError.value = "记忆导入失败：${e.message ?: e.javaClass.simpleName}"
            return false
        }
        return addEntries(entries)
    }

    /** SQLite 正常时以其为准，同时修复缺失或损坏的双备份。 */
    private fun loadMemories(): Unit = synchronized(lock) {
        try {
            val storedEntries = queries.selectAllMemoryEntries().executeAsList().map { it.toEntry() }
            allTurns = queries.selectAllMemoryTurns().executeAsList().map { it.toTurn() }
            if (storedEntries.isNotEmpty()) {
                _memories.value = storedEntries
                _lastError.value = repairBackupsFromDatabase(storedEntries)
                return
            }

            val legacy = readLegacyWithRecovery()
            if (legacy.entries.isEmpty()) {
                _memories.value = emptyList()
                _lastError.value = legacy.warning
            } else {
                val migrated = addEntries(legacy.entries)
                if (migrated && legacy.warning != null) _lastError.value = legacy.warning
            }
        } catch (e: Exception) {
            val recovered = readLegacyWithRecovery()
            _memories.value = recovered.entries.sortedByDescending { it.timestamp }
            allTurns = emptyList()
            val recovery = if (recovered.source != null) {
                "已加载 ${recovered.source.name} 备份，但 SQLite 尚未恢复"
            } else {
                "没有可用的 JSON 备份"
            }
            _lastError.value = "SQLite 读取失败，$recovery：${e.message ?: e.javaClass.simpleName}" +
                (recovered.warning?.let { "；$it" } ?: "")
        }
    }

    private data class LegacyRead(
        val entries: List<MemoryEntry>,
        val source: File?,
        val warning: String?
    )

    private fun readLegacyWithRecovery(): LegacyRead {
        var primaryError: Exception? = null
        if (memoryFile.exists()) {
            try {
                return LegacyRead(decodeEntries(memoryFile), memoryFile, null)
            } catch (e: Exception) {
                primaryError = e
            }
        }
        if (backupFile.exists()) {
            try {
                val entries = decodeEntries(backupFile)
                val restoreErrors = writeStandaloneFiles(mapOf(memoryFile to backupFile.readText()))
                val warning = if (restoreErrors.isEmpty()) {
                    "主记忆备份不可用，已从 ${backupFile.name} 恢复最近可用版本"
                } else {
                    "已读取 ${backupFile.name}，但主备份恢复失败：${restoreErrors.joinToString()}"
                }
                return LegacyRead(entries, backupFile, warning)
            } catch (_: Exception) {
                // 两个备份都不可用时保留损坏现场，不得静默当作空记忆。
            }
        }
        val quarantined = quarantineCorruptBackups()
        val detail = primaryError?.message ?: "备份文件不存在或为空"
        return LegacyRead(
            entries = emptyList(),
            source = null,
            warning = if (memoryFile.exists() || backupFile.exists()) {
                "记忆读取失败：$detail；损坏文件处理结果：$quarantined"
            } else {
                null
            }
        )
    }

    private fun repairBackupsFromDatabase(entries: List<MemoryEntry>): String? {
        fun valid(file: File): Boolean = file.isFile && runCatching { decodeEntries(file) == entries }.getOrDefault(false)
        if (valid(memoryFile) && valid(backupFile)) return null
        val errors = writeStandaloneFiles(backupFiles(entries))
        return if (errors.isEmpty()) {
            "已从 SQLite 重建缺失或损坏的记忆备份"
        } else {
            "SQLite 记忆可用，但备份修复失败：${errors.joinToString()}"
        }
    }

    private fun quarantineCorruptBackups(): String {
        val results = mutableListOf<String>()
        listOf(memoryFile, backupFile).filter { it.exists() }.forEach { file ->
            val target = File(file.parentFile, "${file.name}.corrupt-${UUID.randomUUID()}")
            results += if (file.renameTo(target)) target.name else "无法移动 ${file.name}，原文件已保留"
        }
        return results.joinToString().ifBlank { "无文件可保留" }
    }

    private fun prepareArchive(current: MutableList<MemoryEntry>): ArchivePreparation {
        val archiveCount = current.size - maxMemories + 100
        val lowImportance = current.filter { it.importance <= 3 }
            .sortedBy { it.timestamp }
            .take(archiveCount)
        val lowImportanceIds = lowImportance.mapTo(mutableSetOf()) { it.id }
        val remaining = current.filterNot { it.id in lowImportanceIds }
            .sortedWith(compareBy<MemoryEntry> { it.importance > 3 }.thenBy { it.timestamp })
            .take(archiveCount - lowImportance.size)
        val toArchive = lowImportance + remaining
        current.removeAll(toArchive.toSet())
        val file = File(archiveDir, "archive_${System.currentTimeMillis()}_${UUID.randomUUID()}.json")
        return ArchivePreparation(mapOf(file to encodeEntries(toArchive)))
    }

    private data class ArchivePreparation(val files: Map<File, String>)

    private fun readArchiveEntries(): Map<File, List<MemoryEntry>> {
        val files = archiveDir.listFiles { file ->
            file.isFile && file.name.startsWith("archive_") && file.name.endsWith(".json")
        } ?: return emptyMap()
        return files.associateWith { file ->
            try {
                decodeEntries(file)
            } catch (e: Exception) {
                throw IOException("归档 ${file.name} 读取失败：${e.message}", e)
            }
        }
    }

    private fun obsoleteBackupFiles(): Map<File, String> {
        val parent = memoryFile.parentFile ?: return emptyMap()
        return parent.listFiles { file ->
            file.isFile && file.name.startsWith("${memoryFile.name}.corrupt-")
        }?.associateWith { "[]" } ?: emptyMap()
    }

    private fun backupFiles(entries: List<MemoryEntry>): Map<File, String> {
        val content = encodeEntries(entries)
        return mapOf(memoryFile to content, backupFile to content)
    }

    /** 原文发生任何删除时重建所有受影响日文件，旧文件不会留下已遗忘内容。 */
    private fun rawFiles(turns: List<MemoryTurn>): Map<File, String> {
        val files = mutableMapOf<File, String>()
        rawDir.listFiles { file ->
            file.isFile && file.name.startsWith("conversation-") && file.name.endsWith(".jsonl")
        }?.forEach { files[it] = "" }
        val records = turns.groupBy { it.memoryId }.values.mapNotNull { memoryTurns ->
            val user = memoryTurns.firstOrNull { it.role == "USER" }
            val assistant = memoryTurns.firstOrNull { it.role == "ASSISTANT" }
            val first = memoryTurns.firstOrNull()
            if (first == null || (user == null && assistant == null)) {
                null
            } else {
                ConversationRecord(
                    memoryId = first.memoryId,
                    conversationId = first.conversationId,
                    userText = user?.content ?: "",
                    assistantText = assistant?.content ?: "",
                    timestamp = first.timestamp
                )
            }
        }.sortedWith(compareBy<ConversationRecord> { it.timestamp }.thenBy { it.memoryId })
        records.groupBy { dayFor(it.timestamp) }.forEach { (day, dayRecords) ->
            val content = dayRecords.joinToString("\n") { rawJson.encodeToString(it) } + "\n"
            files[File(rawDir, "conversation-$day.jsonl")] = content
        }
        return files
    }

    private fun dayFor(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))

    private fun commitState(
        newEntries: List<MemoryEntry>,
        newTurns: List<MemoryTurn>,
        extraFiles: Map<File, String>,
        rebuildRaw: Boolean,
        operation: String,
        clearDatabase: Boolean = false
    ): Boolean {
        val oldEntries = _memories.value
        val oldTurns = allTurns
        var transaction: FileTransaction? = null
        return try {
            val files = backupFiles(newEntries) + extraFiles + if (rebuildRaw) rawFiles(newTurns) else emptyMap()
            transaction = FileTransaction(files)
            transaction.prepare()
            database.transaction {
                applyDatabaseDiff(oldEntries, newEntries, oldTurns, newTurns, clearDatabase)
                transaction.commit()
            }
            _memories.value = newEntries.sortedByDescending { it.timestamp }
            allTurns = newTurns
            _lastError.value = null
            true
        } catch (e: Exception) {
            val rollbackErrors = transaction?.rollback().orEmpty()
            val rollback = if (rollbackErrors.isEmpty()) "" else "；回滚异常：${rollbackErrors.joinToString()}"
            _lastError.value = "$operation：${e.message ?: e.javaClass.simpleName}$rollback"
            false
        } finally {
            transaction?.cleanup()
        }
    }

    private fun applyDatabaseDiff(
        oldEntries: List<MemoryEntry>,
        newEntries: List<MemoryEntry>,
        oldTurns: List<MemoryTurn>,
        newTurns: List<MemoryTurn>,
        clearDatabase: Boolean
    ) {
        if (clearDatabase) {
            queries.deleteAllMemoryTurns()
            queries.deleteAllMemoryEntries()
            return
        }
        val newEntryIds = newEntries.mapTo(mutableSetOf()) { it.id }
        oldEntries.filterNot { it.id in newEntryIds }.forEach { queries.deleteMemoryEntry(it.id) }
        val newTurnIds = newTurns.mapTo(mutableSetOf()) { it.id }
        oldTurns.filterNot { it.id in newTurnIds }.forEach { queries.deleteMemoryTurn(it.id) }

        val oldEntryById = oldEntries.associateBy { it.id }
        newEntries.forEach { entry ->
            if (oldEntryById[entry.id] != entry) queries.upsertMemoryEntry(
                id = entry.id,
                type = entry.type.name,
                content = entry.content,
                timestamp = entry.timestamp,
                importance = entry.importance.toLong(),
                metadata = json.encodeToString(entry.metadata)
            )
        }
        val oldTurnById = oldTurns.associateBy { it.id }
        newTurns.forEach { turn ->
            if (oldTurnById[turn.id] != turn) queries.upsertMemoryTurn(
                id = turn.id,
                memory_id = turn.memoryId,
                conversation_id = turn.conversationId,
                role = turn.role,
                content = turn.content,
                timestamp = turn.timestamp
            )
        }
    }

    private fun isSafeToStore(entries: List<MemoryEntry>, turns: List<MemoryTurn>): Boolean {
        val texts = entries.flatMap { entry ->
            listOf(entry.content) + entry.metadata.map { (key, value) -> "$key=$value" }
        } + turns.map { it.content }
        return texts.none { text -> SENSITIVE_PATTERNS.any { it.containsMatchIn(text) } }
    }

    private fun encodeEntries(entries: List<MemoryEntry>): String =
        json.encodeToString(entries.map { MemoryEntryDto.fromEntry(it) })

    private fun decodeEntries(file: File): List<MemoryEntry> {
        val text = file.readText()
        if (text.isBlank()) throw IOException("${file.name} 为空")
        return json.decodeFromString<List<MemoryEntryDto>>(text).map { it.toEntry() }
    }

    private fun writeStandaloneFiles(files: Map<File, String>): List<String> {
        val transaction = FileTransaction(files)
        return try {
            transaction.prepare()
            transaction.commit()
            emptyList()
        } catch (e: Exception) {
            listOf(e.message ?: e.javaClass.simpleName) + transaction.rollback()
        } finally {
            transaction.cleanup()
        }
    }

    /** 同目录暂存、 durable write 和旧内容内存回滚；API 24 可用。 */
    private class FileTransaction(files: Map<File, String>) {
        private data class Item(
            val target: File,
            val bytes: ByteArray,
            val temp: File,
            val oldBytes: ByteArray?
        )

        private val items = files.entries.map { (target, content) ->
            val parent = target.parentFile ?: throw IOException("存储路径没有父目录：$target")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建存储目录：$parent")
            Item(target, content.toByteArray(Charsets.UTF_8), createTemp(target, parent), target.takeIf { it.isFile }?.readBytes())
        }
        private val committed = mutableListOf<Item>()

        fun prepare() {
            items.forEach { item -> writeDurably(item.temp, item.bytes) }
        }

        fun commit() {
            items.forEach { item ->
                if (!item.temp.renameTo(item.target)) throw IOException("无法替换文件：${item.target.name}")
                committed += item
                if (!item.target.isFile || item.target.length() != item.bytes.size.toLong()) {
                    throw IOException("文件写入校验失败：${item.target.name}")
                }
            }
        }

        fun rollback(): List<String> {
            val errors = mutableListOf<String>()
            committed.asReversed().forEach { item ->
                try {
                    val old = item.oldBytes
                    if (old == null) {
                        if (item.target.exists() && !item.target.delete()) throw IOException("无法移除 ${item.target.name}")
                    } else {
                        val temp = createTemp(item.target, requireNotNull(item.target.parentFile))
                        try {
                            writeDurably(temp, old)
                            if (!temp.renameTo(item.target)) throw IOException("无法恢复 ${item.target.name}")
                        } finally {
                            if (temp.exists()) temp.delete()
                        }
                    }
                } catch (e: Exception) {
                    errors += e.message ?: item.target.name
                }
            }
            committed.clear()
            return errors
        }

        fun cleanup() {
            items.forEach { if (it.temp.exists()) it.temp.delete() }
        }

        private fun createTemp(target: File, parent: File): File =
            File.createTempFile("${target.name}.", ".tmp", parent)

        private fun writeDurably(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        }
    }

    private fun cleanupTemporaryFiles() {
        listOfNotNull(memoryFile.parentFile, archiveDir, rawDir).distinct().forEach { dir ->
            dir.listFiles { file -> file.isFile && file.name.endsWith(".tmp") }?.forEach { it.delete() }
        }
    }

    fun getStats(): MemoryStats {
        val all = _memories.value
        return MemoryStats(
            totalCount = all.size,
            conversationCount = all.count { it.type == MemoryType.CONVERSATION_RECORD },
            factCount = all.count { it.type == MemoryType.KEY_FACT },
            preferenceCount = all.count { it.type == MemoryType.PREFERENCE },
            taskCount = all.count { it.type == MemoryType.TASK_RECORD },
            profileCount = all.count { it.type == MemoryType.USER_PROFILE }
        )
    }

    data class MemoryStats(
        val totalCount: Int,
        val conversationCount: Int,
        val factCount: Int,
        val preferenceCount: Int,
        val taskCount: Int,
        val profileCount: Int
    )

    private fun Memory_entries.toEntry() = MemoryEntry(
        id = id,
        type = MemoryType.valueOf(type),
        content = content,
        timestamp = timestamp,
        importance = importance.toInt(),
        metadata = json.decodeFromString(metadata)
    )

    private fun Memory_turns.toTurn() = MemoryTurn(
        id = id,
        memoryId = memory_id,
        conversationId = conversation_id,
        role = role,
        content = content,
        timestamp = timestamp
    )

    @Serializable
    private data class ConversationRecord(
        val memoryId: String,
        val conversationId: String,
        val userText: String,
        val assistantText: String,
        val timestamp: Long
    )

    private companion object {
        const val maxMemories = 1000
        val SENSITIVE_PATTERNS = listOf(
            Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Regex("(?i)\\b(?:password|passwd|pwd|api[ _-]?key|(?:access[ _-]?)?token|secret|credentials?)\\b\\s*(?:is|=|:)\\s*[^\\s,;]+"),
            Regex("(?:密码|口令|凭据|密钥|令牌)\\s*(?:是|为|=|:|：)\\s*[:：]?\\s*[^\\s，。；]+"),
            Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}"),
            Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")
        )
    }
}

@Serializable
data class MemoryEntryDto(
    val id: String,
    val type: String,
    val content: String,
    val timestamp: Long,
    val importance: Int,
    val metadata: Map<String, String>
) {
    fun toEntry(): MemoryEntry = MemoryEntry(
        id = id,
        type = MemoryType.valueOf(type),
        content = content,
        timestamp = timestamp,
        importance = importance,
        metadata = metadata
    )

    companion object {
        fun fromEntry(entry: MemoryEntry): MemoryEntryDto = MemoryEntryDto(
            id = entry.id,
            type = entry.type.name,
            content = entry.content,
            timestamp = entry.timestamp,
            importance = entry.importance,
            metadata = entry.metadata
        )
    }
}

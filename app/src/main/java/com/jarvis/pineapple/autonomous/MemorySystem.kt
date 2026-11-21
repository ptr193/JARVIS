package com.jarvis.pineapple.autonomous

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 记忆条目类型
 */
enum class MemoryType {
    CONVERSATION_RECORD, KEY_FACT, PREFERENCE, TASK_RECORD, USER_PROFILE
}

/**
 * 单条记忆
 */
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

/**
 * 本地长期记忆系统（REQ-11）。
 */
class MemorySystem internal constructor(
    private val memoryFile: File,
    private val archiveDir: File
) {

    constructor(context: Context) : this(
        File(context.filesDir, "memory/memories.json"),
        File(context.filesDir, "memory/archive")
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()

    private val _memories = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val memories: StateFlow<List<MemoryEntry>> = _memories.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val maxMemories = 1000

    init {
        loadMemories()
    }

    fun rememberFact(content: String, importance: Int = 7, metadata: Map<String, String> = emptyMap()): Boolean =
        addEntries(listOf(MemoryEntry(type = MemoryType.KEY_FACT, content = content, importance = importance, metadata = metadata)))

    fun rememberPreference(content: String, importance: Int = 6): Boolean =
        addEntries(listOf(MemoryEntry(type = MemoryType.PREFERENCE, content = content, importance = importance)))

    fun recordConversation(summary: String, importance: Int = 3): Boolean =
        addEntries(listOf(MemoryEntry(type = MemoryType.CONVERSATION_RECORD, content = summary, importance = importance)))

    fun recordTask(taskSummary: String, importance: Int = 5): Boolean =
        addEntries(listOf(MemoryEntry(type = MemoryType.TASK_RECORD, content = taskSummary, importance = importance)))

    fun updateUserProfile(content: String, importance: Int = 8): Boolean =
        addEntries(listOf(MemoryEntry(type = MemoryType.USER_PROFILE, content = content, importance = importance)))

    private fun addEntries(entries: List<MemoryEntry>): Boolean = synchronized(lock) {
        val current = _memories.value.toMutableList()
        entries.forEach { entry ->
            val existingIdx = current.indexOfFirst {
                it.type == entry.type && it.content == entry.content
            }
            if (existingIdx >= 0) current[existingIdx] = entry else current.add(entry)
        }

        if (current.size > maxMemories && !archiveOldMemories(current)) return false
        val sorted = current.sortedByDescending { it.timestamp }
        if (!saveMemories(sorted)) return false
        _memories.value = sorted
        true
    }

    fun search(query: String, limit: Int = 10): List<MemoryEntry> {
        val keywords = query.lowercase().split(" ", "，", "。", "？", "！", "、")
            .filter { it.length >= 2 }

        return _memories.value
            .map { entry ->
                val score = keywords.count { kw ->
                    entry.content.lowercase().contains(kw)
                } + (entry.importance / 10f)
                entry to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun getByType(type: MemoryType): List<MemoryEntry> {
        return _memories.value.filter { it.type == type }
    }

    fun getRecent(limit: Int = 20): List<MemoryEntry> {
        return _memories.value.sortedByDescending { it.timestamp }.take(limit)
    }

    fun getImportant(threshold: Int = 7): List<MemoryEntry> {
        return _memories.value.filter { it.importance >= threshold }
    }

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
        val updated = _memories.value.filterNot { it.id == memoryId }
        if (!saveMemories(updated)) return false
        _memories.value = updated
        true
    }

    fun forgetByType(type: MemoryType): Boolean = synchronized(lock) {
        val updated = _memories.value.filterNot { it.type == type }
        if (!saveMemories(updated)) return false
        _memories.value = updated
        true
    }

    fun clearAll(): Boolean = synchronized(lock) {
        if (!saveMemories(emptyList())) return false
        _memories.value = emptyList()
        true
    }

    fun exportMemories(): String {
        val dtos = _memories.value.map { MemoryEntryDto.fromEntry(it) }
        return json.encodeToString(dtos)
    }

    fun importMemories(jsonStr: String): Boolean {
        val entries = try {
            json.decodeFromString<List<MemoryEntryDto>>(jsonStr).map { it.toEntry() }
        } catch (e: Exception) {
            _lastError.value = "记忆导入失败：${e.message ?: e.javaClass.simpleName}"
            return false
        }
        return addEntries(entries)
    }

    private fun saveMemories(entries: List<MemoryEntry>): Boolean {
        return try {
            val dtos = entries.map { MemoryEntryDto.fromEntry(it) }
            writeAtomically(memoryFile, json.encodeToString(dtos))
            _lastError.value = null
            true
        } catch (e: Exception) {
            _lastError.value = "记忆保存失败：${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    private fun loadMemories() {
        try {
            if (memoryFile.exists() && memoryFile.length() > 0) {
                val dtos = json.decodeFromString<List<MemoryEntryDto>>(memoryFile.readText())
                _memories.value = dtos.map { it.toEntry() }
                    .sortedByDescending { it.timestamp }
            }
            _lastError.value = null
        } catch (e: Exception) {
            _memories.value = emptyList()
            _lastError.value = "记忆读取失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun archiveOldMemories(current: MutableList<MemoryEntry>): Boolean {
        val archiveCount = current.size - maxMemories + 100
        val lowImportance = current
            .filter { it.importance <= 3 }
            .sortedBy { it.timestamp }
            .take(archiveCount)
        val lowImportanceIds = lowImportance.mapTo(mutableSetOf()) { it.id }
        val remaining = current
            .filterNot { it.id in lowImportanceIds }
            .sortedWith(compareBy<MemoryEntry> { it.importance > 3 }.thenBy { it.timestamp })
            .take(archiveCount - lowImportance.size)
        val toArchive = lowImportance + remaining
        if (toArchive.isEmpty()) return true

        return try {
            val archiveFile = File(archiveDir, "archive_${System.currentTimeMillis()}_${System.nanoTime()}.json")
            val dtos = toArchive.map { MemoryEntryDto.fromEntry(it) }
            writeAtomically(archiveFile, json.encodeToString(dtos))
            current.removeAll(toArchive.toSet())
            true
        } catch (e: Exception) {
            _lastError.value = "记忆归档失败：${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    private fun writeAtomically(file: File, content: String) {
        val parent = file.parentFile ?: throw IOException("存储路径没有父目录")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建存储目录：$parent")
        val temp = File(parent, "${file.name}.tmp")
        try {
            temp.writeText(content)
            if (!temp.renameTo(file)) throw IOException("无法写入文件：${file.name}")
        } finally {
            if (temp.exists()) temp.delete()
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

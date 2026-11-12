package com.jarvis.pineapple.autonomous

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
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

    private val maxMemories = 1000

    init {
        if (!memoryFile.parentFile!!.exists()) memoryFile.parentFile!!.mkdirs()
        if (!archiveDir.exists()) archiveDir.mkdirs()
        loadMemories()
    }

    fun rememberFact(content: String, importance: Int = 7, metadata: Map<String, String> = emptyMap()) {
        addEntry(MemoryEntry(type = MemoryType.KEY_FACT, content = content, importance = importance, metadata = metadata))
    }

    fun rememberPreference(content: String, importance: Int = 6) {
        addEntry(MemoryEntry(type = MemoryType.PREFERENCE, content = content, importance = importance))
    }

    fun recordConversation(summary: String, importance: Int = 3) {
        addEntry(MemoryEntry(type = MemoryType.CONVERSATION_RECORD, content = summary, importance = importance))
    }

    fun recordTask(taskSummary: String, importance: Int = 5) {
        addEntry(MemoryEntry(type = MemoryType.TASK_RECORD, content = taskSummary, importance = importance))
    }

    fun updateUserProfile(content: String, importance: Int = 8) {
        addEntry(MemoryEntry(type = MemoryType.USER_PROFILE, content = content, importance = importance))
    }

    private fun addEntry(entry: MemoryEntry) {
        synchronized(lock) {
            val current = _memories.value.toMutableList()

            val existingIdx = current.indexOfFirst {
                it.type == entry.type && it.content == entry.content
            }
            if (existingIdx >= 0) {
                current[existingIdx] = entry
            } else {
                current.add(entry)
            }

            if (current.size > maxMemories) {
                archiveOldMemories(current)
            }

            _memories.value = current.sortedByDescending { it.timestamp }
            saveMemories()
        }
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

    fun forget(memoryId: String) {
        synchronized(lock) {
            _memories.value = _memories.value.filterNot { it.id == memoryId }
            saveMemories()
        }
    }

    fun forgetByType(type: MemoryType) {
        synchronized(lock) {
            _memories.value = _memories.value.filterNot { it.type == type }
            saveMemories()
        }
    }

    fun clearAll() {
        synchronized(lock) {
            _memories.value = emptyList()
            saveMemories()
        }
    }

    fun exportMemories(): String {
        val dtos = _memories.value.map { MemoryEntryDto.fromEntry(it) }
        return json.encodeToString(dtos)
    }

    fun importMemories(jsonStr: String): Boolean {
        return try {
            val dtos = json.decodeFromString<List<MemoryEntryDto>>(jsonStr)
            dtos.forEach { dto ->
                addEntry(dto.toEntry())
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun saveMemories() {
        try {
            val dtos = _memories.value.map { MemoryEntryDto.fromEntry(it) }
            memoryFile.writeText(json.encodeToString(dtos))
        } catch (e: Exception) {
            // 保存失败时静默处理
        }
    }

    private fun loadMemories() {
        try {
            if (memoryFile.exists()) {
                val dtos = json.decodeFromString<List<MemoryEntryDto>>(memoryFile.readText())
                _memories.value = dtos.map { it.toEntry() }
                    .sortedByDescending { it.timestamp }
            }
        } catch (e: Exception) {
            _memories.value = emptyList()
        }
    }

    private fun archiveOldMemories(current: MutableList<MemoryEntry>) {
        val toArchive = current
            .filter { it.importance <= 3 }
            .sortedBy { it.timestamp }
            .take(current.size - maxMemories + 100)

        if (toArchive.isNotEmpty()) {
            try {
                val archiveFile = File(archiveDir, "archive_${System.currentTimeMillis()}.json")
                val dtos = toArchive.map { MemoryEntryDto.fromEntry(it) }
                archiveFile.writeText(json.encodeToString(dtos))
                current.removeAll(toArchive.toSet())
            } catch (e: Exception) {
                current.sortBy { it.timestamp }
                repeat(100) { if (current.isNotEmpty()) current.removeAt(0) }
            }
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

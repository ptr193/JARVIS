package com.jarvis.pineapple.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 对话历史持久化仓库。
 * 使用 JSON 文件存储对话历史，支持多会话管理。
 */
class ConversationRepository(
    private val context: Context? = null,
    conversationsDirOverride: File? = null
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private val conversationsDir: File = conversationsDirOverride
        ?: File(requireNotNull(context) { "ConversationRepository 需要 Context 或显式目录" }.filesDir, "conversations")

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        if (!conversationsDir.isDirectory && !conversationsDir.mkdirs()) {
            _lastError.value = "对话存储目录不可用：$conversationsDir"
        } else {
            loadConversations()
        }
    }

    /** 创建并落盘成功后才切换当前会话；失败返回 null 且保留原状态。 */
    fun createConversation(title: String = "新对话"): Conversation? = synchronized(lock) {
        val conversation = Conversation(title = title)
        if (!saveConversation(conversation)) return null
        _conversations.value = _conversations.value + conversation
        _currentConversationId.value = conversation.id
        conversation
    }

    fun switchConversation(conversationId: String): Boolean = synchronized(lock) {
        if (_conversations.value.none { it.id == conversationId }) {
            _lastError.value = "对话切换失败：会话不存在"
            return false
        }
        _currentConversationId.value = conversationId
        _lastError.value = null
        true
    }

    fun getCurrentConversation(): Conversation? = synchronized(lock) {
        val id = _currentConversationId.value ?: return null
        _conversations.value.firstOrNull { it.id == id }
    }

    fun updateMessages(messages: List<ChatMessage>): Boolean = synchronized(lock) {
        val id = _currentConversationId.value
        val current = _conversations.value.firstOrNull { it.id == id }
        if (id == null || current == null) {
            _lastError.value = "对话保存失败：当前会话不存在"
            return false
        }
        val updated = current.copy(messages = messages.toMutableList())
        if (!saveConversation(updated)) return false
        _conversations.value = _conversations.value.map { if (it.id == id) updated else it }
        true
    }

    fun deleteConversation(conversationId: String): Boolean = synchronized(lock) {
        val file = File(conversationsDir, "$conversationId.json")
        if (file.exists() && !file.delete()) {
            _lastError.value = "对话删除失败：${file.name}"
            return false
        }
        _conversations.value = _conversations.value.filterNot { it.id == conversationId }
        if (_currentConversationId.value == conversationId) {
            _currentConversationId.value = _conversations.value.firstOrNull()?.id
        }
        _lastError.value = null
        true
    }

    fun renameConversation(conversationId: String, newTitle: String): Boolean = synchronized(lock) {
        val current = _conversations.value.firstOrNull { it.id == conversationId } ?: return false
        val updated = current.copy(title = newTitle)
        if (!saveConversation(updated)) return false
        _conversations.value = _conversations.value.map { if (it.id == conversationId) updated else it }
        true
    }

    private fun saveConversation(conversation: Conversation): Boolean {
        return try {
            val file = File(conversationsDir, "${conversation.id}.json")
            val dto = ConversationDto.fromConversation(conversation)
            writeAtomically(file, json.encodeToString(dto))
            _lastError.value = null
            true
        } catch (e: Exception) {
            _lastError.value = "对话保存失败：${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    private fun loadConversations() {
        val failures = mutableListOf<String>()
        try {
            val files = conversationsDir.listFiles { f -> f.extension == "json" } ?: emptyArray()
            val loaded = files.mapNotNull { file ->
                try {
                    val dto = json.decodeFromString<ConversationDto>(file.readText())
                    dto.toConversation()
                } catch (e: Exception) {
                    failures.add(file.name)
                    null
                }
            }.sortedByDescending { it.createdAt }
            _conversations.value = loaded
            if (_currentConversationId.value == null && loaded.isNotEmpty()) {
                _currentConversationId.value = loaded.first().id
            }
            _lastError.value = if (failures.isEmpty()) null else "部分对话读取失败：${failures.joinToString()}"
        } catch (e: Exception) {
            _conversations.value = emptyList()
            _lastError.value = "对话读取失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun exportAll(): String {
        val dtos = _conversations.value.map { ConversationDto.fromConversation(it) }
        return json.encodeToString(dtos)
    }

    /** 导出单个对话为 JSON；不存在时返回 null。 */
    fun exportConversation(conversationId: String): String? {
        val conversation = _conversations.value.firstOrNull { it.id == conversationId } ?: return null
        return json.encodeToString(ConversationDto.fromConversation(conversation))
    }

    /** 归档/取消归档；持久化成功后才更新内存状态。 */
    fun setArchived(conversationId: String, archived: Boolean): Boolean = synchronized(lock) {
        val current = _conversations.value.firstOrNull { it.id == conversationId } ?: return false
        val updated = current.copy(archived = archived)
        if (!saveConversation(updated)) return false
        _conversations.value = _conversations.value.map {
            if (it.id == conversationId) updated else it
        }
        true
    }

    fun importFromJson(jsonStr: String): Boolean = synchronized(lock) {
        // 必须先解析并校验完整批次，任何错误都发生在写入之前。
        val imported = try {
            val dtos = try {
                json.decodeFromString<List<ConversationDto>>(jsonStr)
            } catch (_: Exception) {
                listOf(json.decodeFromString<ConversationDto>(jsonStr))
            }
            val result = dtos.map { it.toConversation() }
            validateImport(result)
            result
        } catch (e: Exception) {
            _lastError.value = "对话导入失败：${e.message ?: e.javaClass.simpleName}"
            return false
        }
        if (imported.isEmpty()) {
            _lastError.value = null
            return true
        }

        val staged = mutableListOf<StagedImport>()
        val committed = mutableListOf<StagedImport>()
        try {
            // 同目录暂存新文件和旧文件备份，保证 rename 不跨文件系统（API 24 可用）。
            imported.forEach { conversation ->
                val target = File(conversationsDir, "${conversation.id}.json")
                val temp = createStagingFile(target, ".tmp")
                val item = StagedImport(target = target, temp = temp)
                staged += item
                writeDurably(temp, json.encodeToString(ConversationDto.fromConversation(conversation)).toByteArray())
                if (target.isFile) {
                    val backup = createStagingFile(target, ".bak")
                    item.backup = backup
                    writeDurably(backup, target.readBytes())
                }
            }
            staged.forEach { item ->
                if (!item.temp.renameTo(item.target)) {
                    throw IOException("无法替换文件：${item.target.name}")
                }
                committed += item
            }
        } catch (e: Exception) {
            val rollbackErrors = rollbackImport(committed)
            staged.forEach {
                it.temp.delete()
                it.backup?.delete()
            }
            val rollbackDetail = if (rollbackErrors.isEmpty()) "" else "；回滚异常：${rollbackErrors.joinToString()}"
            _lastError.value = "对话导入失败，本批未生效：${e.message ?: e.javaClass.simpleName}$rollbackDetail"
            return false
        }

        staged.forEach { it.backup?.delete() }
        val importedIds = imported.mapTo(mutableSetOf()) { it.id }
        val merged = (_conversations.value.filterNot { it.id in importedIds } + imported)
            .sortedByDescending { it.createdAt }
        _conversations.value = merged
        val currentId = _currentConversationId.value
        if (currentId == null || merged.none { it.id == currentId }) {
            _currentConversationId.value = merged.firstOrNull()?.id
        }
        _lastError.value = null
        true
    }

    private fun validateImport(imported: List<Conversation>) {
        imported.forEachIndexed { index, conversation ->
            if (!SAFE_CONVERSATION_ID.matches(conversation.id)) {
                throw IllegalArgumentException("第 ${index + 1} 个对话 ID 不能安全用作文件名")
            }
        }
        if (imported.map { it.id }.toSet().size != imported.size) {
            throw IllegalArgumentException("同一批次包含重复对话 ID")
        }
    }

    private data class StagedImport(
        val target: File,
        val temp: File,
        var backup: File? = null
    )

    private fun rollbackImport(committed: List<StagedImport>): List<String> {
        val errors = mutableListOf<String>()
        committed.asReversed().forEach { item ->
            try {
                val backup = item.backup
                if (backup != null) {
                    if (!backup.renameTo(item.target)) throw IOException("无法恢复 ${item.target.name}")
                } else if (item.target.exists() && !item.target.delete()) {
                    throw IOException("无法移除 ${item.target.name}")
                }
            } catch (e: Exception) {
                errors += e.message ?: item.target.name
            }
        }
        return errors
    }

    private fun createStagingFile(target: File, suffix: String): File {
        val parent = target.parentFile ?: throw IOException("存储路径没有父目录")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建存储目录：$parent")
        return File.createTempFile("${target.name}.", suffix, parent)
    }

    private fun writeDurably(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun writeAtomically(file: File, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val temp = createStagingFile(file, ".tmp")
        try {
            writeDurably(temp, bytes)
            if (!temp.renameTo(file)) throw IOException("无法写入文件：${file.name}")
            if (!file.isFile || file.length() != bytes.size.toLong()) {
                throw IOException("文件写入校验失败：${file.name}")
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private companion object {
        val SAFE_CONVERSATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
    }
}

@Serializable
data class ConversationDto(
    val id: String,
    val title: String,
    val messages: List<MessageDto>,
    val createdAt: Long,
    val archived: Boolean = false
) {
    fun toConversation(): Conversation = Conversation(
        id = id,
        title = title,
        messages = messages.map { it.toChatMessage() }.toMutableList(),
        createdAt = createdAt,
        archived = archived
    )

    companion object {
        fun fromConversation(conv: Conversation): ConversationDto = ConversationDto(
            id = conv.id,
            title = conv.title,
            messages = conv.messages.map { MessageDto.fromChatMessage(it) },
            createdAt = conv.createdAt,
            archived = conv.archived
        )
    }
}

@Serializable
data class MessageDto(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val isStreaming: Boolean
) {
    fun toChatMessage(): ChatMessage = ChatMessage(
        id = id,
        role = Role.valueOf(role),
        content = content,
        timestamp = timestamp,
        isStreaming = isStreaming
    )

    companion object {
        fun fromChatMessage(msg: ChatMessage): MessageDto = MessageDto(
            id = msg.id,
            role = msg.role.name,
            content = msg.content,
            timestamp = msg.timestamp,
            isStreaming = msg.isStreaming
        )
    }
}

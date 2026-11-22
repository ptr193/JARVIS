package com.jarvis.pineapple.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
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

    fun createConversation(title: String = "新对话"): Conversation = synchronized(lock) {
        val conversation = Conversation(title = title)
        saveConversation(conversation)
        _conversations.value = _conversations.value + conversation
        _currentConversationId.value = conversation.id
        conversation
    }

    fun switchConversation(conversationId: String): Boolean = synchronized(lock) {
        if (_conversations.value.none { it.id == conversationId }) return false
        _currentConversationId.value = conversationId
        true
    }

    fun getCurrentConversation(): Conversation? = synchronized(lock) {
        val id = _currentConversationId.value ?: return null
        _conversations.value.firstOrNull { it.id == id }
    }

    fun updateMessages(messages: List<ChatMessage>): Boolean = synchronized(lock) {
        val id = _currentConversationId.value ?: return false
        val current = _conversations.value.firstOrNull { it.id == id } ?: return false
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
        // 兼容两种导出格式：全部对话（数组）与单个对话（对象）。
        val conversations = try {
            json.decodeFromString<List<ConversationDto>>(jsonStr).map { it.toConversation() }
        } catch (listError: Exception) {
            try {
                listOf(json.decodeFromString<ConversationDto>(jsonStr).toConversation())
            } catch (e: Exception) {
                _lastError.value = "对话导入失败：${e.message ?: e.javaClass.simpleName}"
                return false
            }
        }
        if (conversations.any { !saveConversation(it) }) return false
        loadConversations()
        true
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

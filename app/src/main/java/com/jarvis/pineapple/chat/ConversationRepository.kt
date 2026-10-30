package com.jarvis.pineapple.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 对话历史持久化仓库。
 * 使用 JSON 文件存储对话历史，支持多会话管理。
 */
class ConversationRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val conversationsDir = File(context.filesDir, "conversations")

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    init {
        if (!conversationsDir.exists()) conversationsDir.mkdirs()
        loadConversations()
    }

    fun createConversation(title: String = "新对话"): Conversation {
        val conversation = Conversation(title = title)
        _conversations.value = _conversations.value + conversation
        _currentConversationId.value = conversation.id
        saveConversation(conversation)
        return conversation
    }

    fun switchConversation(conversationId: String) {
        _currentConversationId.value = conversationId
    }

    fun getCurrentConversation(): Conversation? {
        val id = _currentConversationId.value ?: return null
        return _conversations.value.firstOrNull { it.id == id }
    }

    fun updateMessages(messages: List<ChatMessage>) {
        val id = _currentConversationId.value ?: return
        _conversations.value = _conversations.value.map { conv ->
            if (conv.id == id) {
                val updated = conv.copy(messages = messages.toMutableList())
                saveConversation(updated)
                updated
            } else conv
        }
    }

    fun deleteConversation(conversationId: String) {
        _conversations.value = _conversations.value.filterNot { it.id == conversationId }
        File(conversationsDir, "$conversationId.json").delete()
        if (_currentConversationId.value == conversationId) {
            _currentConversationId.value = _conversations.value.firstOrNull()?.id
        }
    }

    fun renameConversation(conversationId: String, newTitle: String) {
        _conversations.value = _conversations.value.map { conv ->
            if (conv.id == conversationId) {
                val updated = conv.copy(title = newTitle)
                saveConversation(updated)
                updated
            } else conv
        }
    }

    private fun saveConversation(conversation: Conversation) {
        try {
            val file = File(conversationsDir, "${conversation.id}.json")
            val dto = ConversationDto.fromConversation(conversation)
            file.writeText(json.encodeToString(dto))
        } catch (e: Exception) {
            // 保存失败时静默处理
        }
    }

    private fun loadConversations() {
        try {
            val files = conversationsDir.listFiles { f -> f.extension == "json" } ?: emptyArray()
            val loaded = files.mapNotNull { file ->
                runCatching {
                    val dto = json.decodeFromString<ConversationDto>(file.readText())
                    dto.toConversation()
                }.getOrNull()
            }.sortedByDescending { it.createdAt }
            _conversations.value = loaded
            if (_currentConversationId.value == null && loaded.isNotEmpty()) {
                _currentConversationId.value = loaded.first().id
            }
        } catch (e: Exception) {
            _conversations.value = emptyList()
        }
    }

    fun exportAll(): String {
        val dtos = _conversations.value.map { ConversationDto.fromConversation(it) }
        return json.encodeToString(dtos)
    }

    fun importFromJson(jsonStr: String): Boolean {
        return try {
            val dtos = json.decodeFromString<List<ConversationDto>>(jsonStr)
            dtos.forEach { dto ->
                val conv = dto.toConversation()
                saveConversation(conv)
            }
            loadConversations()
            true
        } catch (e: Exception) {
            false
        }
    }
}

@Serializable
data class ConversationDto(
    val id: String,
    val title: String,
    val messages: List<MessageDto>,
    val createdAt: Long
) {
    fun toConversation(): Conversation = Conversation(
        id = id,
        title = title,
        messages = messages.map { it.toChatMessage() }.toMutableList(),
        createdAt = createdAt
    )

    companion object {
        fun fromConversation(conv: Conversation): ConversationDto = ConversationDto(
            id = conv.id,
            title = conv.title,
            messages = conv.messages.map { MessageDto.fromChatMessage(it) },
            createdAt = conv.createdAt
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

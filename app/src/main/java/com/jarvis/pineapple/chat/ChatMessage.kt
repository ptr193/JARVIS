package com.jarvis.pineapple.chat

/**
 * 对话消息角色
 */
enum class Role { USER, ASSISTANT, SYSTEM }

/**
 * 单条对话消息
 */
data class ChatMessage(
    val id: String = generateId(),
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
) {
    companion object {
        private var counter = 0
        fun generateId(): String = "msg_${System.currentTimeMillis()}_${counter++}"
    }
}

/**
 * 对话会话
 */
data class Conversation(
    val id: String = generateId(),
    val title: String = "新对话",
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        private var counter = 0
        fun generateId(): String = "conv_${System.currentTimeMillis()}_${counter++}"
    }
}

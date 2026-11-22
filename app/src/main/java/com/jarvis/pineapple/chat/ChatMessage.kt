package com.jarvis.pineapple.chat

import java.util.concurrent.atomic.AtomicInteger

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
        private val counter = AtomicInteger(0)
        fun generateId(): String = "msg_${System.nanoTime()}_${counter.getAndIncrement()}"
    }
}

/**
 * 对话会话
 */
data class Conversation(
    val id: String = generateId(),
    val title: String = "新对话",
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    /** 归档后从默认历史列表隐藏，可在“已归档”分组查看/恢复。 */
    val archived: Boolean = false
) {
    companion object {
        private val counter = AtomicInteger(0)
        fun generateId(): String = "conv_${System.nanoTime()}_${counter.getAndIncrement()}"
    }
}

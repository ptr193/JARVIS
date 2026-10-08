package com.jarvis.pineapple.chat

import com.jarvis.pineapple.model.AiModel
import com.jarvis.pineapple.model.ModelScheduler
import com.jarvis.pineapple.personality.PersonalityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach

/**
 * 对话引擎。
 * - 多轮对话上下文管理
 * - 消息发送与接收
 * - 流式回复
 * - 对话历史持久化
 * - 人格 prompt 注入
 */
class ChatEngine(
    private val modelScheduler: ModelScheduler,
    private val personalityRepository: PersonalityRepository
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val conversation = Conversation()

    fun getConversation(): Conversation = conversation

    /**
     * 发送用户消息并获取 AI 流式回复。
     */
    suspend fun sendUserMessage(content: String): Flow<String> {
        val userMsg = ChatMessage(role = Role.USER, content = content)
        conversation.messages.add(userMsg)
        _messages.value = conversation.messages.toList()

        _isGenerating.value = true
        val model: AiModel = modelScheduler.getActive()
        val personality = personalityRepository.personality.first()
        val systemPrompt = personalityRepository.buildSystemPrompt(personality)

        // 构建上下文（最近 20 条）
        val context = buildContextPrompt()
        val fullPrompt = buildString {
            append(context)
            append("\n用户：").append(content)
            append("\nJARVIS：")
        }

        // 先添加占位 AI 消息用于流式显示
        val aiMsg = ChatMessage(role = Role.ASSISTANT, content = "", isStreaming = true)
        conversation.messages.add(aiMsg)
        _messages.value = conversation.messages.toList()

        val flow = model.stream(fullPrompt, systemPrompt)
        val collected = StringBuilder()

        return flow
            .onEach { chunk ->
                collected.append(chunk)
                val idx = conversation.messages.indexOfLast { it.isStreaming }
                if (idx >= 0) {
                    conversation.messages[idx] = conversation.messages[idx].copy(content = collected.toString())
                    _messages.value = conversation.messages.toList()
                }
            }
            .onCompletion {
                val idx = conversation.messages.indexOfLast { it.isStreaming }
                if (idx >= 0) {
                    conversation.messages[idx] = conversation.messages[idx].copy(isStreaming = false)
                    _messages.value = conversation.messages.toList()
                }
                _isGenerating.value = false
            }
    }

    private fun buildContextPrompt(): String {
        val recent = conversation.messages.takeLast(20)
        return recent.joinToString("\n") { msg ->
            when (msg.role) {
                Role.USER -> "用户：${msg.content}"
                Role.ASSISTANT -> "JARVIS：${msg.content}"
                Role.SYSTEM -> ""
            }
        }
    }

    fun clearConversation() {
        conversation.messages.clear()
        _messages.value = emptyList()
    }
}

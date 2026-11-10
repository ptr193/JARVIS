package com.jarvis.pineapple.assistant

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList

object OverlayReplyCoordinator {
    const val ENGINE_UNAVAILABLE = "对话引擎不可用"
    const val EMPTY_REPLY = "未能生成回答"

    fun buildPrompt(userText: String, screenContext: String): String {
        val spoken = userText.trim()
        if (spoken.isEmpty()) return ""
        val screen = screenContext.trim()
        return if (screen.isEmpty()) spoken
        else "当前屏幕内容：$screen\n用户说：$spoken"
    }

    fun shouldSpeak(reply: String): Boolean = reply.isNotBlank()

    suspend fun collectReply(flow: Flow<String>): String {
        return try {
            val text = flow.toList().joinToString("").trim()
            text.ifBlank { EMPTY_REPLY }
        } catch (e: Exception) {
            "回答失败：${e.message ?: e.javaClass.simpleName}"
        }
    }
}

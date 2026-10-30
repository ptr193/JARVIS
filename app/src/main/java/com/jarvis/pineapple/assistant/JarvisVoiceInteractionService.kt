package com.jarvis.pineapple.assistant

import android.service.voice.VoiceInteractionService

/**
 * 系统默认数字助手服务。
 * 用户可在系统设置中将 JARVIS 设为默认助手，长按 Home 唤起。
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // 助手服务就绪
    }
}

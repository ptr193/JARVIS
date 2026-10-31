package com.jarvis.pineapple.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.jarvis.pineapple.ui.MainActivity
import com.jarvis.pineapple.wake.HomeLongPressDetector

/**
 * 语音交互会话服务。
 */
class JarvisVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return JarvisVoiceInteractionSession(this)
    }
}

class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        HomeLongPressDetector.notifyHomeLongPress()
        startVoiceActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

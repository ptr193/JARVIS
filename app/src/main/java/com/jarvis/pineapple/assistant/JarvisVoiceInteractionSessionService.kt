package com.jarvis.pineapple.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.jarvis.pineapple.R
import com.jarvis.pineapple.wake.HomeLongPressDetector

/**
 * 语音交互会话服务。长按 Home 时展示 overlay，不拉起主界面。
 */
class JarvisVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return JarvisVoiceInteractionSession(this)
    }
}

class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private var waveform: VoiceWaveformView? = null
    private var hint: TextView? = null

    override fun onCreateContentView(): View {
        val view = LayoutInflater.from(context).inflate(R.layout.voice_assist_overlay, null)
        waveform = view.findViewById(R.id.assist_waveform)
        hint = view.findViewById(R.id.assist_hint)
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        HomeLongPressDetector.notifyHomeLongPress()
        hint?.setText(R.string.assist_listening)
        waveform?.rms = 0.35f
    }

    override fun onHide() {
        super.onHide()
        waveform?.rms = 0f
    }
}

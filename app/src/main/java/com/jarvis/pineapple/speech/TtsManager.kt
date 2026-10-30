package com.jarvis.pineapple.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 语音合成（TTS）。
 * 默认 JARVIS 中文声线（系统 TTS，声线自备）。
 */
class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.CHINESE
            ready = true
        }
    }

    fun speak(text: String) {
        if (ready) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_tts")
        }
    }

    fun shutdown() {
        tts?.shutdown()
    }
}

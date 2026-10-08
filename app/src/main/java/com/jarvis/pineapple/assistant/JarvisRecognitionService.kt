package com.jarvis.pineapple.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService

/**
 * 语音识别服务（占位，预留 ASR 接入）。
 */
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

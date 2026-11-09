package com.jarvis.pineapple.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jarvis.pineapple.R
import com.jarvis.pineapple.wake.HomeLongPressDetector
import java.util.Locale

/**
 * 语音交互会话服务。长按 Home 时展示 overlay 并听写，不拉起主界面。
 */
class JarvisVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return JarvisVoiceInteractionSession(this)
    }
}

class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private var waveform: VoiceWaveformView? = null
    private var hint: TextView? = null
    private var recognizer: SpeechRecognizer? = null

    override fun onCreateContentView(): View {
        val view = LayoutInflater.from(context).inflate(R.layout.voice_assist_overlay, null)
        waveform = view.findViewById(R.id.assist_waveform)
        hint = view.findViewById(R.id.assist_hint)
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        HomeLongPressDetector.notifyHomeLongPress()
        startDictation()
    }

    override fun onHide() {
        super.onHide()
        stopDictation()
        waveform?.rms = 0f
    }

    private fun startDictation() {
        stopDictation()
        hint?.setText(R.string.assist_listening)
        waveform?.rms = 0.2f

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            hint?.setText(R.string.assist_mic_denied)
            waveform?.rms = 0f
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            hint?.setText(R.string.assist_asr_unavailable)
            waveform?.rms = 0f
            return
        }

        val speech = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                hint?.setText(R.string.assist_listening)
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                waveform?.rms = normalized
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                hint?.setText(R.string.assist_processing)
            }

            override fun onError(errorCode: Int) {
                waveform?.rms = 0f
                hint?.text = asrErrorMessage(errorCode)
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                waveform?.rms = 0f
                if (text.isBlank()) {
                    hint?.setText(R.string.assist_no_match)
                } else {
                    hint?.text = text
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) hint?.text = text
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        speech.startListening(intent)
    }

    private fun stopDictation() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    companion object {
        fun asrErrorMessage(errorCode: Int): String = when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "未授予麦克风权限"
            SpeechRecognizer.ERROR_NETWORK -> "网络错误"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
            SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙"
            SpeechRecognizer.ERROR_SERVER -> "服务器错误"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时"
            else -> "语音识别失败"
        }
    }
}

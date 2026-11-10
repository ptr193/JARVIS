package com.jarvis.pineapple.assistant

import android.Manifest
import android.app.assist.AssistStructure
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
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jarvis.pineapple.R
import com.jarvis.pineapple.chat.ChatEngine
import com.jarvis.pineapple.speech.SpeechManager
import com.jarvis.pineapple.wake.HomeLongPressDetector
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * 语音交互会话服务。长按 Home 时展示 overlay 并听写、回答、识屏，不拉起主界面。
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
    private var screenContext: String = ""
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var answerJob: Job? = null

    override fun onCreateContentView(): View {
        val view = LayoutInflater.from(context).inflate(R.layout.voice_assist_overlay, null)
        waveform = view.findViewById(R.id.assist_waveform)
        hint = view.findViewById(R.id.assist_hint)
        view.findViewById<Button>(R.id.assist_object).setOnClickListener {
            openCamera(OverlayCameraController.TASK_OBJECT)
        }
        view.findViewById<Button>(R.id.assist_document).setOnClickListener {
            openCamera(OverlayCameraController.TASK_DOCUMENT)
        }
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        HomeLongPressDetector.notifyHomeLongPress()
        startDictation()
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: android.app.assist.AssistContent?
    ) {
        super.onHandleAssist(data, structure, content)
        applyAssistStructure(structure)
    }

    override fun onHide() {
        super.onHide()
        stopDictation()
        waveform?.rms = 0f
        answerJob?.cancel()
        answerJob = null
    }

    private fun applyAssistStructure(structure: AssistStructure?) {
        if (structure == null) {
            screenContext = ""
            return
        }
        val texts = mutableListOf<String>()
        for (i in 0 until structure.windowNodeCount) {
            collectNodeText(structure.getWindowNodeAt(i).rootViewNode, texts)
        }
        screenContext = AssistStructureReader.collectTexts(texts.asSequence())
    }

    private fun collectNodeText(node: AssistStructure.ViewNode?, sink: MutableList<String>) {
        if (node == null) return
        node.text?.toString()?.let { sink.add(it) }
        node.contentDescription?.toString()?.let { sink.add(it) }
        node.hint?.toString()?.let { sink.add(it) }
        for (i in 0 until node.childCount) {
            collectNodeText(node.getChildAt(i), sink)
        }
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
                    answer(text)
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

    private fun answer(userText: String) {
        val prompt = OverlayReplyCoordinator.buildPrompt(userText, screenContext)
        if (prompt.isEmpty()) {
            hint?.setText(R.string.assist_no_match)
            return
        }
        hint?.setText(R.string.assist_thinking)
        val engine = runCatching { GlobalContext.get().get<ChatEngine>() }.getOrNull()
        val speech = runCatching { GlobalContext.get().get<SpeechManager>() }.getOrNull()
        if (engine == null) {
            hint?.text = OverlayReplyCoordinator.ENGINE_UNAVAILABLE
            return
        }
        answerJob?.cancel()
        answerJob = sessionScope.launch {
            val reply = runCatching {
                withContext(Dispatchers.Default) {
                    OverlayReplyCoordinator.collectReply(engine.sendUserMessage(prompt))
                }
            }.getOrElse { "回答失败：${it.message ?: it.javaClass.simpleName}" }
            hint?.text = reply
            if (speech != null && OverlayReplyCoordinator.shouldSpeak(reply)) {
                speech.speak(reply)
            }
        }
    }

    private fun openCamera(task: String) {
        val intent = Intent(context, OverlayCameraActivity::class.java)
            .putExtra(OverlayCameraController.EXTRA_TASK, task)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { hint?.text = OverlayCameraController.OPEN_FAILED }
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

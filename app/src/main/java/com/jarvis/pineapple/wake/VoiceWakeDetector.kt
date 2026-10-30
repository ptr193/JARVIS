package com.jarvis.pineapple.wake

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.pineapple.speech.WakeWordEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * 语音唤醒检测器（本地、实时）。
 *
 * 三种工作模式：
 *  - [WakeMode.SIMPLE]    能量阈值 VAD（低功耗，粗略检测）
 *  - [WakeMode.TEMPLATE]  MFCC + DTW 模板匹配（默认，需先 [enrollWakeWord] 录入模板）
 *  - [WakeMode.SYSTEM_ASR] 系统语音识别文本比对唤醒词
 *
 * SIMPLE/TEMPLATE 模式全部在本机处理，音频不外传（REQ-10 隐私要求）。
 */
class VoiceWakeDetector(private val context: Context) {

    enum class WakeMode { SIMPLE, TEMPLATE, SYSTEM_ASR }

    private var job: Job? = null
    private var record: android.media.AudioRecord? = null
    private var asr: SpeechRecognizer? = null

    @Volatile var wakeWord: String = "嘿 JARVIS"
    @Volatile var wakeMode: WakeMode = WakeMode.TEMPLATE
    @Volatile private var sensitivity: Float = 0.5f

    /** 设置灵敏度（0..1，越大越容易触发）。 */
    fun setSensitivity(value: Float) {
        sensitivity = value.coerceIn(0f, 1f)
    }

    private val _detected = MutableStateFlow(false)
    val detected: StateFlow<Boolean> = _detected.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /** 唤醒回调（唤醒词命中时触发，附置信度）。 */
    var onWake: ((Float) -> Unit)? = null

    private val templateDir = File(context.filesDir, "wakewords").apply { mkdirs() }

    // ==================== 生命周期 ====================

    @SuppressLint("MissingPermission")
    fun start() {
        if (job?.isActive == true) return
        if (!hasAudioPermission()) {
            Log.e(TAG, "缺少录音权限，无法启动唤醒监听")
            return
        }
        _detected.value = false
        _listening.value = true
        job = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            if (wakeMode == WakeMode.SYSTEM_ASR) runSystemAsrLoop() else runAudioLoop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _listening.value = false
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
        try {
            asr?.stopListening()
            asr?.destroy()
        } catch (_: Exception) {
        }
        asr = null
    }

    fun updateWakeWord(word: String) {
        wakeWord = word.trim().ifBlank { "嘿 JARVIS" }
    }

    fun updateWakeMode(mode: WakeMode) {
        if (wakeMode != mode) {
            stop()
            wakeMode = mode
            start()
        }
    }

    /** 读取并清零一次唤醒事件。 */
    fun consumeDetection(): Boolean {
        if (_detected.value) {
            _detected.value = false
            return true
        }
        return false
    }

    // ==================== 实时音频处理 ====================

    @SuppressLint("MissingPermission")
    private suspend fun runAudioLoop() {
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            WakeWordEngine.SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "无法获取音频缓冲区大小")
            return
        }
        val bufferSize = maxOf(minBuf, WakeWordEngine.FRAME_LEN * 8)
        record = try {
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                WakeWordEngine.SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord 创建失败", e)
            return
        }
        if (record?.state != android.media.AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 未初始化")
            return
        }

        val vad = WakeWordEngine.Vad()
        val frame = ShortArray(WakeWordEngine.FRAME_LEN)
        val segment = ArrayList<Short>(WakeWordEngine.SAMPLE_RATE)
        var inSpeech = false
        var silenceFrames = 0

        try {
            record?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording 失败", e)
            return
        }

        while (job?.isActive == true) {
            val read = try {
                record?.read(frame, 0, frame.size) ?: 0
            } catch (e: Exception) {
                Log.e(TAG, "读取音频失败", e)
                -1
            }
            if (read <= 0) continue

            when (wakeMode) {
                WakeMode.SIMPLE -> if (isSpeechEnergy(frame, read)) trigger(0.6f)
                WakeMode.TEMPLATE -> {
                    val speech = vad.isSpeech(frame)
                    if (!inSpeech) {
                        if (speech) {
                            inSpeech = true
                            silenceFrames = 0
                            segment.clear()
                            for (i in 0 until read) segment.add(frame[i])
                        }
                    } else {
                        for (i in 0 until read) segment.add(frame[i])
                        if (speech) silenceFrames = 0 else silenceFrames++
                        val tooLong = segment.size >= (WakeWordEngine.SAMPLE_RATE * 2.5f).toInt()
                        if (silenceFrames > MAX_SILENCE_FRAMES || tooLong) {
                            val clip = ShortArray(segment.size) { segment[it] }
                            val score = matchTemplate(clip)
                            if (score > 0f) trigger(score)
                            segment.clear()
                            inSpeech = false
                            silenceFrames = 0
                        }
                    }
                }
                WakeMode.SYSTEM_ASR -> Unit
            }
        }
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
    }

    private fun isSpeechEnergy(frame: ShortArray, read: Int): Boolean {
        var sum = 0.0
        for (i in 0 until read) sum += frame[i].toDouble() * frame[i]
        val rms = kotlin.math.sqrt(sum / read.coerceAtLeast(1))
        val threshold = (0.02f + sensitivity * 0.1f) * 32767.0
        return rms > threshold
    }

    // ==================== 系统识别模式 ====================

    private suspend fun runSystemAsrLoop() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "设备不支持系统语音识别")
            return
        }
        while (job?.isActive == true) {
            val done = CompletableDeferred<Unit>()
            asr = try {
                SpeechRecognizer.createSpeechRecognizer(context)
            } catch (e: Exception) {
                Log.e(TAG, "识别器创建失败", e)
                return
            }
            asr?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { done.complete(Unit) }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.joinToString(" ") ?: ""
                    if (matchesWakeWord(text)) trigger(0.8f)
                    done.complete(Unit)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.joinToString(" ") ?: ""
                    if (matchesWakeWord(text)) trigger(0.75f)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            try {
                asr?.startListening(intent)
                withTimeoutOrNull(8000) { done.await() }
            } catch (e: Exception) {
                Log.e(TAG, "系统识别异常", e)
            }
            try { asr?.destroy() } catch (_: Exception) {}
            asr = null
            delay(150)
        }
    }

    private fun matchesWakeWord(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = text.replace(" ", "").lowercase()
        val full = wakeWord.replace(" ", "").lowercase()
        if (full.isNotEmpty() && normalized.contains(full)) return true
        return wakeWord.split(" ", "，", ",")
            .filter { it.length >= 2 }
            .any { normalized.contains(it.lowercase()) }
    }

    // ==================== 模板录入与匹配 ====================

    /** 录入唤醒词模板（传入一次清晰的唤醒词语音，16kHz 单声道 PCM）。 */
    fun enrollWakeWord(samples: ShortArray): Boolean {
        if (samples.size < WakeWordEngine.SAMPLE_RATE / 4) return false
        val trimmed = WakeWordEngine.trimSilence(samples)
        val effective = if (trimmed.size >= WakeWordEngine.FRAME_LEN) trimmed else samples
        val seq = WakeWordEngine.cmn(WakeWordEngine.mfccSequence(effective))
        if (seq.size < 5) return false
        return try {
            val sb = StringBuilder()
            for (frame in seq) sb.append(frame.joinToString(",")).append('\n')
            templateFile().writeText(sb.toString())
            true
        } catch (e: Exception) {
            Log.e(TAG, "模板保存失败", e)
            false
        }
    }

    fun hasTemplate(): Boolean = templateFile().let { it.exists() && it.length() > 0 }

    fun clearTemplate() { templateFile().delete() }

    private fun templateFile(): File {
        val safe = wakeWord.replace(Regex("[^\\p{L}\\p{N}]"), "_")
        return File(templateDir, "wake_$safe.tpl")
    }

    private fun loadTemplate(): Array<FloatArray>? {
        val file = templateFile()
        if (!file.exists()) return null
        return try {
            file.readLines().filter { it.isNotBlank() }.map { line ->
                line.split(",").map { it.toFloat() }.toFloatArray()
            }.toTypedArray().takeIf { it.size >= 5 }
        } catch (e: Exception) {
            Log.e(TAG, "模板读取失败", e)
            null
        }
    }

    /** 用已录入模板匹配语音，返回 0-1 的相似度得分。 */
    fun matchTemplate(samples: ShortArray): Float {
        val template = loadTemplate() ?: return 0f
        val seq = WakeWordEngine.cmn(WakeWordEngine.mfccSequence(samples))
        if (seq.size < 5) return 0f
        val dist = WakeWordEngine.dtw(seq, template)
        val threshold = 6.0f - sensitivity * 3.0f
        if (dist >= threshold) return 0f
        return (1f - dist / threshold).coerceIn(0f, 1f)
    }

    // ==================== 工具 ====================

    private fun trigger(score: Float) {
        _detected.value = true
        onWake?.invoke(score)
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "VoiceWakeDetector"
        private const val MAX_SILENCE_FRAMES = 12
    }
}

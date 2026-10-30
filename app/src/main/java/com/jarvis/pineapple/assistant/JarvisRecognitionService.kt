package com.jarvis.pineapple.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.sqrt

/**
 * JARVIS 语音识别服务（系统 `SpeechRecognizer` 的识别后端）。
 *
 * 这是一个真实可用的 [RecognitionService] 实现，工作流程：
 * 1. 通过 [android.media.AudioRecord] 以 16 kHz / 单声道 / 16-bit 采集麦克风 PCM；
 * 2. 逐帧计算 RMS 并通过 `Callback.rmsChanged` 上报音量；
 * 3. 自适应噪声门限做端点检测（VAD），检测到语音结束或超时后封装为 WAV；
 * 4. 将 WAV 以 `POST` 发送到 `jarvis_speech` 偏好中配置的 ASR 端点，
 *    解析 `{"text": "...", "confidence": 0.9}` 并通过 `Callback.results` 返回。
 *
 * ASR 端点通过 `jarvis_speech` SharedPreferences 的 `asr_endpoint` 配置
 * （例如 `https://your-asr.example.com/recognize`）。未配置或网络不可用时，
 * 返回明确的错误码（不伪造识别结果）。
 */
class JarvisRecognitionService : RecognitionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    @Volatile
    private var activeCallback: Callback? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        if (listener == null) return
        activeCallback = listener

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            activeCallback = null
            return
        }

        listener.readyForSpeech(Bundle())
        captureJob?.cancel()
        captureJob = scope.launch { runCapture(listener) }
    }

    override fun onStopListening(listener: Callback?) {
        // 停止采集；若用户主动结束而语音尚在处理，交由 runCapture 收尾
        activeCallback = null
    }

    override fun onCancel(listener: Callback?) {
        activeCallback = null
        captureJob?.cancel()
        captureJob = null
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    // ==================== 采集与识别 ====================

    private suspend fun runCapture(listener: Callback) {
        val sampleRate = SAMPLE_RATE
        val frameSize = sampleRate * FRAME_MS / 1000
        val recorder = AudioCapture(sampleRate, frameSize)
        if (!recorder.start()) {
            listener.error(SpeechRecognizer.ERROR_AUDIO)
            return
        }

        val utterance = ByteArrayOutputStream()
        var noiseFloor = 0.0
        var speechStarted = false
        var silenceFrames = 0
        var speechFrames = 0
        var totalFrames = 0
        var sawSpeech = false

        try {
            val buffer = ShortArray(frameSize)
            val start = SystemClock.elapsedRealtime()
            while (scope.isActive) {
                val read = recorder.read(buffer)
                if (read <= 0) continue
                totalFrames++

                val rms = rms(buffer, read)
                listener.rmsChanged(rms.toFloat())

                val isSpeech = rms > (noiseFloor * SPEECH_FACTOR).coerceAtLeast(MIN_SPEECH_RMS)
                if (!speechStarted) {
                    if (isSpeech) {
                        speechStarted = true
                        sawSpeech = true
                        speechFrames++
                        listener.beginningOfSpeech()
                        appendPcm(utterance, buffer, read)
                    } else {
                        // 仅更新噪声底噪
                        noiseFloor = noiseFloor * 0.95 + rms * 0.05
                    }
                } else {
                    appendPcm(utterance, buffer, read)
                    if (isSpeech) {
                        speechFrames++
                        silenceFrames = 0
                    } else {
                        silenceFrames++
                    }
                }

                val elapsed = SystemClock.elapsedRealtime() - start
                val speechDuration = speechFrames * FRAME_MS
                val silenceDuration = silenceFrames * FRAME_MS

                if (speechStarted && silenceDuration >= SILENCE_END_MS) break
                if (speechDuration >= MAX_UTTERANCE_MS) break
                if (!speechStarted && elapsed >= NO_SPEECH_TIMEOUT_MS) {
                    listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    return
                }
                if (elapsed >= HARD_TIMEOUT_MS) break
            }

            if (!sawSpeech || utterance.size() < sampleRate / 10) {
                listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                return
            }

            listener.endOfSpeech()
            val wav = encodeWav(utterance.toByteArray(), sampleRate)
            recognize(wav, listener)
        } catch (e: Exception) {
            Log.w(TAG, "语音采集失败: ${e.message}")
            listener.error(SpeechRecognizer.ERROR_AUDIO)
        } finally {
            recorder.stop()
        }
    }

    private suspend fun recognize(wav: ByteArray, listener: Callback) {
        val endpoint = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_ENDPOINT, "")?.trim().orEmpty()
        if (endpoint.isEmpty()) {
            Log.i(TAG, "未配置 ASR 端点，返回 ERROR_CLIENT")
            listener.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            listener.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        val locale = Locale.getDefault().toLanguageTag()
        val url = URL("$endpoint?language=$locale&sampleRate=$SAMPLE_RATE")
        var connection: HttpURLConnection? = null
        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "audio/wav")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(wav) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "ASR 端点返回 HTTP $code")
                listener.error(SpeechRecognizer.ERROR_SERVER)
                return
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
            val text = json?.optString("text", "")?.trim().orEmpty()
            if (text.isEmpty()) {
                listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                return
            }
            val confidence = json?.optDouble("confidence", 0.9)?.toFloat() ?: 0.9f
            val bundle = Bundle().apply {
                putStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION,
                    arrayListOf(text)
                )
                putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(confidence))
            }
            listener.results(bundle)
            Log.i(TAG, "识别成功: $text")
        } catch (e: Exception) {
            Log.w(TAG, "ASR 请求失败: ${e.message}")
            listener.error(SpeechRecognizer.ERROR_NETWORK)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    // ==================== 工具 ====================

    /** 将 16-bit PCM 采样以小端序追加到输出流。 */
    private fun appendPcm(out: ByteArrayOutputStream, buffer: ShortArray, length: Int) {
        if (length <= 0) return
        val bytes = ByteArray(length * 2)
        var j = 0
        for (i in 0 until length) {
            val v = buffer[i].toInt()
            bytes[j++] = (v and 0xFF).toByte()
            bytes[j++] = ((v shr 8) and 0xFF).toByte()
        }
        out.write(bytes)
    }

    private fun rms(buffer: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val v = buffer[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / count)
    }

    private fun encodeWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val byteRate = sampleRate * 2
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeInt(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun writeShort(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        writeAscii("RIFF")
        writeInt(36 + pcm.size)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeInt(16)
        writeShort(1)          // PCM
        writeShort(1)          // mono
        writeInt(sampleRate)
        writeInt(byteRate)
        writeShort(2)          // block align
        writeShort(16)         // bits per sample
        writeAscii("data")
        writeInt(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /** 独立的 AudioRecord 封装，保证资源释放。 */
    private class AudioCapture(private val sampleRate: Int, private val frameSize: Int) {
        private var record: android.media.AudioRecord? = null

        fun start(): Boolean {
            val minBuffer = android.media.AudioRecord.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) return false
            val bufferSize = maxOf(minBuffer, frameSize * 2 * 4)
            val recorder = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (recorder.state != android.media.AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return false
            }
            return runCatching {
                recorder.startRecording()
                record = recorder
                true
            }.getOrDefault(false)
        }

        fun read(buffer: ShortArray): Int = record?.read(buffer, 0, buffer.size) ?: -1

        fun stop() {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            record = null
        }
    }





    companion object {
        private const val TAG = "JarvisRecognitionService"
        private const val PREFS = "jarvis_speech"
        private const val KEY_ENDPOINT = "asr_endpoint"

        private const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 20
        private const val SPEECH_FACTOR = 3.0
        private const val MIN_SPEECH_RMS = 300.0
        private const val SILENCE_END_MS = 700L
        private const val MAX_UTTERANCE_MS = 15_000L
        private const val NO_SPEECH_TIMEOUT_MS = 5_000L
        private const val HARD_TIMEOUT_MS = 20_000L
    }
}

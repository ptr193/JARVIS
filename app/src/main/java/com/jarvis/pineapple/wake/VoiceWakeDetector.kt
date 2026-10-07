package com.jarvis.pineapple.wake

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 语音唤醒：常驻麦克风监听 + 唤醒词检测。
 * 默认唤醒词"嘿 JARVIS"，可自定义。
 * 预留接入轻量级唤醒词引擎（如 PocketSphinx / 本地 KWS）。
 */
class VoiceWakeDetector(private val context: Context) {

    private var job: Job? = null
    var wakeWord: String = "嘿 JARVIS"

    private val _detected = MutableStateFlow(false)
    val detected: StateFlow<Boolean> = _detected.asStateFlow()

    fun start() {
        job = CoroutineScope(Dispatchers.IO).launch {
            // 预留：初始化 AudioRecord 并进行唤醒词检测
            // 此处为占位，实际需接入 KWS 引擎
            val minBuf = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            try {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf
                )
                record.startRecording()
                val buffer = ShortArray(minBuf)
                while (isActive()) {
                    record.read(buffer, 0, minBuf)
                    // 预留：将音频送入唤醒词检测引擎
                }
                record.stop()
                record.release()
            } catch (e: Exception) {
                // 麦克风权限未授予
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun isActive(): Boolean = job?.isActive == true

    /** 模拟唤醒词命中（供测试） */
    fun simulateDetection() {
        _detected.value = true
    }
}

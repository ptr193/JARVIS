package com.jarvis.pineapple.speech

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import com.jarvis.pineapple.wake.VoiceWakeDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 语音唤醒管理器（REQ-10）。
 *
 * 功能：
 * - 持续监听语音输入
 * - 自动判断语音是否在呼唤 JARVIS 本机
 * - 识别成功后才进入响应
 * - 避免误唤醒与误响应
 */
class VoiceWakeManager(private val context: Context) {

    // ==================== 唤醒状态 ====================

    /**
     * 唤醒状态
     */
    data class WakeState(
        val isListening: Boolean = false,
        val isWoken: Boolean = false,
        val wakeWord: String = "JARVIS",
        val confidence: Float = 0f,
        val sensitivity: WakeSensitivity = WakeSensitivity.MEDIUM,
        val error: String? = null
    )

    enum class WakeSensitivity {
        LOW,    // 低灵敏度（减少误唤醒）
        MEDIUM, // 中等灵敏度
        HIGH    // 高灵敏度（可能增加误唤醒）
    }

    private val _wakeState = MutableStateFlow(WakeState())
    val wakeState: StateFlow<WakeState> = _wakeState.asStateFlow()

    // ==================== 唤醒配置 ====================

    /**
     * 唤醒配置
     */
    data class WakeConfig(
        val wakeWords: List<String> = listOf("JARVIS", "嘿 JARVIS", "你好 JARVIS"),
        val sensitivity: WakeSensitivity = WakeSensitivity.MEDIUM,
        val isLocalProcessing: Boolean = true,  // 本地处理（不上传音频）
        val isAudioRecordingEnabled: Boolean = false,  // 是否保存音频
        val isFeedbackSoundEnabled: Boolean = true,     // 是否播放提示音
        val isMultiDeviceCoordination: Boolean = false  // 多设备协同
    )

    private var wakeConfig = WakeConfig()

    /** 底层语音唤醒检测器（本地 MFCC/DTW 或系统 ASR）。 */
    private val detector = VoiceWakeDetector(context)

    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 70)
    } catch (e: Exception) {
        null
    }

    /**
     * 配置唤醒
     */
    fun configure(config: WakeConfig) {
        wakeConfig = config
        detector.wakeWord = config.wakeWords.firstOrNull() ?: "JARVIS"
        detector.setSensitivity(
            when (config.sensitivity) {
                WakeSensitivity.LOW -> 0.3f
                WakeSensitivity.MEDIUM -> 0.5f
                WakeSensitivity.HIGH -> 0.8f
            }
        )
        _wakeState.value = _wakeState.value.copy(
            wakeWord = config.wakeWords.firstOrNull() ?: "JARVIS",
            sensitivity = config.sensitivity
        )
    }

    /**
     * 获取唤醒配置
     */
    fun getWakeConfig(): WakeConfig = wakeConfig

    // ==================== 唤醒控制 ====================

    /**
     * 开始监听唤醒词：接入 [VoiceWakeDetector]，检测命中后回调 [onWakeDetected]。
     */
    fun startListening() {
        detector.wakeWord = wakeConfig.wakeWords.firstOrNull() ?: "JARVIS"
        detector.onWake = { score -> onWakeWordDetected(score) }
        detector.start()
        _wakeState.value = _wakeState.value.copy(
            isListening = detector.listening.value,
            isWoken = false,
            error = if (detector.listening.value) null else "无法启动语音唤醒（请检查录音权限）"
        )
    }

    /**
     * 停止监听唤醒词
     */
    fun stopListening() {
        detector.stop()
        _wakeState.value = _wakeState.value.copy(
            isListening = false
        )
    }

    /** 释放底层资源。 */
    fun release() {
        detector.stop()
        toneGenerator?.release()
    }

    /**
     * 触发唤醒
     */
    fun onWakeWordDetected(confidence: Float) {
        if (confidence >= getSensitivityThreshold()) {
            _wakeState.value = _wakeState.value.copy(
                isWoken = true,
                confidence = confidence
            )

            // 多设备协同：通知其他设备
            if (wakeConfig.isMultiDeviceCoordination) {
                notifyOtherDevices()
            }

            // 播放提示音
            if (wakeConfig.isFeedbackSoundEnabled) {
                playFeedbackSound()
            }
        }
    }

    /**
     * 重置唤醒状态
     */
    fun resetWakeState() {
        _wakeState.value = _wakeState.value.copy(
            isWoken = false,
            confidence = 0f
        )
    }

    /**
     * 检查是否在监听
     */
    fun isListening(): Boolean {
        return _wakeState.value.isListening
    }

    /**
     * 检查是否已唤醒
     */
    fun isWoken(): Boolean {
        return _wakeState.value.isWoken
    }

    // ==================== 灵敏度阈值 ====================

    private fun getSensitivityThreshold(): Float {
        return when (wakeConfig.sensitivity) {
            WakeSensitivity.LOW -> 0.85f
            WakeSensitivity.MEDIUM -> 0.70f
            WakeSensitivity.HIGH -> 0.55f
        }
    }

    // ==================== 辅助方法 ====================

    private fun notifyOtherDevices() {
        try {
            val deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            val intent = Intent(ACTION_WAKE_BROADCAST).apply {
                setPackage(context.packageName)
                putExtra("wake_word", wakeConfig.wakeWords.firstOrNull() ?: "JARVIS")
                putExtra("origin_device", deviceId)
                putExtra("timestamp", System.currentTimeMillis())
            }
            context.sendBroadcast(intent)
            Log.d("VoiceWakeManager", "已广播多设备唤醒协同事件")
        } catch (e: Exception) {
            Log.w("VoiceWakeManager", "广播多设备唤醒失败", e)
        }
    }

    private fun playFeedbackSound() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: Exception) {
            Log.w("VoiceWakeManager", "播放提示音失败", e)
        }
    }

    // ==================== 误唤醒处理 ====================

    /**
     * 误唤醒统计
     */
    data class FalseWakeStatistics(
        val totalDetections: Int = 0,
        val falseDetections: Int = 0,
        val falseWakeRate: Float = 0f,
        val lastFalseWakeTime: Long = 0
    )

    private val _falseWakeStats = MutableStateFlow(FalseWakeStatistics())
    val falseWakeStats: StateFlow<FalseWakeStatistics> = _falseWakeStats.asStateFlow()

    /**
     * 记录误唤醒
     */
    fun recordFalseWake() {
        val stats = _falseWakeStats.value
        val newStats = stats.copy(
            totalDetections = stats.totalDetections + 1,
            falseDetections = stats.falseDetections + 1,
            falseWakeRate = (stats.falseDetections + 1).toFloat() / (stats.totalDetections + 1),
            lastFalseWakeTime = System.currentTimeMillis()
        )
        _falseWakeStats.value = newStats
    }

    /**
     * 记录正确唤醒
     */
    fun recordCorrectWake() {
        val stats = _falseWakeStats.value
        _falseWakeStats.value = stats.copy(
            totalDetections = stats.totalDetections + 1,
            falseWakeRate = stats.falseDetections.toFloat() / (stats.totalDetections + 1)
        )
    }

    /**
     * 检查是否需要提示用户调整灵敏度
     */
    fun shouldSuggestSensitivityAdjustment(): Boolean {
        val stats = _falseWakeStats.value
        return stats.totalDetections > 10 && stats.falseWakeRate > 0.3f
    }

    /**
     * 获取灵敏度调整建议
     */
    fun getSensitivityAdjustmentSuggestion(): String {
        val stats = _falseWakeStats.value
        return when {
            stats.falseWakeRate > 0.5f -> "误唤醒率较高，建议降低灵敏度或更换唤醒词"
            stats.falseWakeRate > 0.3f -> "误唤醒率略高，建议适当降低灵敏度"
            else -> "当前灵敏度设置合适"
        }
    }

    // ==================== 唤醒词管理 ====================

    /**
     * 添加自定义唤醒词
     */
    fun addWakeWord(word: String) {
        val currentWords = wakeConfig.wakeWords.toMutableList()
        if (currentWords.none { it.equals(word, ignoreCase = true) }) {
            currentWords.add(word)
            wakeConfig = wakeConfig.copy(wakeWords = currentWords)
        }
    }

    /**
     * 移除唤醒词
     */
    fun removeWakeWord(word: String) {
        val currentWords = wakeConfig.wakeWords.toMutableList()
        currentWords.removeAll { it.equals(word, ignoreCase = true) }
        if (currentWords.isNotEmpty()) {
            wakeConfig = wakeConfig.copy(wakeWords = currentWords)
        }
    }

    /**
     * 获取所有唤醒词
     */
    fun getWakeWords(): List<String> {
        return wakeConfig.wakeWords
    }

    // ==================== 隐私保护 ====================

    /**
     * 检查隐私设置
     */
    fun checkPrivacySettings(): PrivacySettings {
        return PrivacySettings(
            isLocalProcessing = wakeConfig.isLocalProcessing,
            isAudioRecordingEnabled = wakeConfig.isAudioRecordingEnabled,
            audioRetentionDays = if (wakeConfig.isAudioRecordingEnabled) 7 else 0,
            isCloudSyncEnabled = false
        )
    }

    data class PrivacySettings(
        val isLocalProcessing: Boolean,
        val isAudioRecordingEnabled: Boolean,
        val audioRetentionDays: Int,
        val isCloudSyncEnabled: Boolean
    )

    /**
     * 获取隐私说明
     */
    fun getPrivacyDescription(): String {
        return buildString {
            appendLine("语音唤醒隐私说明：")
            appendLine()
            if (wakeConfig.isLocalProcessing) {
                appendLine("✓ 唤醒检测在本地完成，音频不会上传到云端")
            } else {
                appendLine("✗ 唤醒检测需要联网，音频可能上传到云端")
            }
            if (wakeConfig.isAudioRecordingEnabled) {
                appendLine("• 唤醒音频将保存 ${checkPrivacySettings().audioRetentionDays} 天后自动删除")
            } else {
                appendLine("✓ 不会保存唤醒音频")
            }
        }
    }

    companion object {
        /** 多设备唤醒协同广播（与 BootReceiver 中注册的 action 一致）。 */
        const val ACTION_WAKE_BROADCAST = "com.jarvis.pineapple.WAKE"
    }
}

package com.jarvis.pineapple.wake

import android.content.Context
import android.provider.Settings
import android.util.Log
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

/**
 * 长按 Home 键（默认数字助手）唤醒。
 *
 * 本应用声明为 VoiceInteractionService。当被设为“默认数字助手”后，系统在
 * 长按 Home（或电源键长按）时会拉起本服务并回调 [onHomeLongPress]。
 * 本检测器持续监控数字助手角色状态，并暴露唤醒事件。
 */
class HomeLongPressDetector(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _detected = MutableStateFlow(false)
    val detected: StateFlow<Boolean> = _detected.asStateFlow()

    private val _isAssistant = MutableStateFlow(false)
    val isAssistant: StateFlow<Boolean> = _isAssistant.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                _isAssistant.value = isDefaultAssistant()
                delay(2000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 当前应用是否为系统默认数字助手。 */
    fun isDefaultAssistant(): Boolean {
        return try {
            val service = Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
                ?: Settings.Secure.getString(context.contentResolver, "assistant")
            service != null && service.contains(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /** 由 VoiceInteractionService 回调触发（长按 Home）。 */
    fun onHomeLongPress() {
        _detected.value = true
        Log.i(TAG, "Home 长按唤醒事件")
    }

    /** 消费唤醒事件。 */
    fun consume() {
        _detected.value = false
    }

    companion object {
        private const val TAG = "HomeLongPressDetector"
    }
}

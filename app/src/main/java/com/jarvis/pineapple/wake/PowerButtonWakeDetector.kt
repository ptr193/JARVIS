package com.jarvis.pineapple.wake

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 电源键连按唤醒。
 *
 * 电源键事件只能由 [android.accessibilityservice.AccessibilityService] 捕获
 * （见 `JarvisAccessibilityService.onKeyEvent`）。该服务完成连按判定并调用
 * [notifyPowerWake] 通知本检测器；本检测器同时支持自行判定，两条路径都会
 * 将 [detected] 置为 true，由 `WakeEngine` 汇总分发。
 */
class PowerButtonWakeDetector(@Suppress("UNUSED_PARAMETER") private val context: Context) {

    @Volatile
    private var active = false

    private var lastPressTime = 0L
    private var pressCount = 0

    private val _detected = MutableStateFlow(false)
    val detected: StateFlow<Boolean> = _detected.asStateFlow()

    fun start() {
        active = true
        activeInstance = this
    }

    fun stop() {
        active = false
        pressCount = 0
        if (activeInstance === this) activeInstance = null
    }

    /** 由 AccessibilityService 回调（本检测器自行完成连按判定）。 */
    fun onPowerButtonPressed() {
        if (!active) return
        val now = System.currentTimeMillis()
        if (now - lastPressTime in 1..DOUBLE_PRESS_MS) {
            pressCount++
        } else {
            pressCount = 1
        }
        lastPressTime = now
        if (pressCount >= REQUIRED_PRESSES) {
            _detected.value = true
            pressCount = 0
        }
    }

    /** 读取并清除唤醒标记。 */
    fun consume(): Boolean {
        if (!_detected.value) return false
        _detected.value = false
        return true
    }

    private fun notifyExternalWake() {
        _detected.value = true
    }

    companion object {
        private const val REQUIRED_PRESSES = 3
        private const val DOUBLE_PRESS_MS = 1_000L

        @Volatile
        private var activeInstance: PowerButtonWakeDetector? = null

        /** 供 AccessibilityService 在已判定唤醒时直接通知。 */
        fun notifyPowerWake() {
            activeInstance?.notifyExternalWake()
        }
    }
}

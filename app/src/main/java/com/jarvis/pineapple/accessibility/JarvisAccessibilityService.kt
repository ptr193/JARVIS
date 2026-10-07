package com.jarvis.pineapple.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * JARVIS 辅助功能服务。
 * - 监听电源键连按 2-3 次触发唤醒
 * - 提供跨应用 UI 操作能力（点击/滑动/输入）
 */
class JarvisAccessibilityService : AccessibilityService() {

    private var lastPowerPressTime = 0L
    private var powerPressCount = 0
    private val powerPressWindowMs = 800L
    private val requiredPresses = 3

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 跨应用操作的事件入口（UI 节点查找等）
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event?.keyCode == KeyEvent.KEYCODE_POWER && event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastPowerPressTime < powerPressWindowMs) {
                powerPressCount++
            } else {
                powerPressCount = 1
            }
            lastPowerPressTime = now
            if (powerPressCount >= requiredPresses) {
                Log.i("JARVIS", "电源键连按唤醒触发")
                powerPressCount = 0
                triggerWake()
            }
        }
        return super.onKeyEvent(event)
    }

    private fun triggerWake() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        intent?.let { startActivity(it) }
    }

    /**
     * 跨应用操作：执行点击
     */
    fun performClick(x: Float, y: Float) {
        // 通过 dispatchGesture 实现点击
        android.graphics.Path().apply {
            moveTo(x, y)
        }.let { path ->
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            dispatchGesture(gesture, null, null)
        }
    }
}

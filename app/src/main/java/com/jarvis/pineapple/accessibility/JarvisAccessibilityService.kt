package com.jarvis.pineapple.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

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
     * 跨应用操作：执行点击（需 API 24+）
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun performClick(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }
}

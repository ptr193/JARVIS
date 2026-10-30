package com.jarvis.pineapple.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.jarvis.pineapple.wake.PowerButtonWakeDetector

/**
 * JARVIS 辅助功能服务。
 * - 监听电源键连按 3 次触发唤醒
 * - 提供跨应用 UI 操作能力（点击 / 滑动 / 文本输入 / 全局返回与主页）
 * - 记录前台应用与当前窗口，供远程协助与本机自动化使用
 */
class JarvisAccessibilityService : AccessibilityService() {

    private var lastPowerPressTime = 0L
    private var powerPressCount = 0
    private val powerPressWindowMs = 800L
    private val requiredPresses = 3

    /** 当前前台应用包名。 */
    @Volatile
    var currentPackageName: String? = null
        private set

    /** 当前前台 Activity 类名。 */
    @Volatile
    var currentClassName: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        Log.i(TAG, "JARVIS 辅助功能服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                event.packageName?.let { currentPackageName = it.toString() }
                event.className?.let { currentClassName = it.toString() }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        Log.i(TAG, "JARVIS 辅助功能服务已断开")
        return super.onUnbind(intent)
    }

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
                Log.i(TAG, "电源键连按唤醒触发")
                powerPressCount = 0
                triggerWake()
            }
        }
        return super.onKeyEvent(event)
    }

    private fun triggerWake() {
        PowerButtonWakeDetector.notifyPowerWake()
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        intent?.let { startActivity(it) }
    }

    /**
     * 跨应用操作：在指定坐标执行点击（需 API 24+）
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun performClick(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 跨应用操作：执行滑动（需 API 24+）
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 向当前焦点的输入框写入文本（需 API 21+）。
     */
    fun performTextInput(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editText = findFocusedEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 全局返回。 */
    fun performGlobalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** 全局主页。 */
    fun performGlobalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditable(child)
            if (result != null) return result
        }
        return null
    }

    companion object {
        private const val TAG = "JarvisAccessibility"

        /** 当前运行中的服务实例，供其他组件直接调用 UI 操作能力。 */
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        /** 辅助功能服务是否已启用并连接。 */
        fun isConnected(): Boolean = instance != null
    }
}

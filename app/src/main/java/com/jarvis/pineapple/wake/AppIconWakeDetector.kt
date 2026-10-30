package com.jarvis.pineapple.wake

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 应用图标双击 / 长按唤醒（功能：唤醒源之一）。
 *
 * Android 不允许应用直接监听桌面图标的点击，因此这里采用真实可行的方案：
 * 1. 注册一个动态快捷方式（图标长按菜单）；
 * 2. 入口 `MainActivity` 每次被启动时调用 [onIconAction]；
 * 3. 连续两次启动间隔小于 [DOUBLE_TAP_MS] 即判定为“双击图标唤醒”，
 *    长按快捷方式则直接判定为唤醒。
 */
class AppIconWakeDetector(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var resetJob: Job? = null

    private var lastTapTime = 0L
    private var tapCount = 0

    private val _detected = MutableStateFlow(false)
    val detected: StateFlow<Boolean> = _detected.asStateFlow()

    /** 注册动态快捷方式（真实写入 ShortcutManager）。 */
    fun start() {
        createShortcut()
    }
    fun stop() {
        resetJob?.cancel()
        resetJob = null
        tapCount = 0
    }

    /**
     * 由入口 Activity 在从桌面图标 / 快捷方式启动时调用。
     *
     * @param actionType [ACTION_TAP] 普通点击（用于双击判定）或 [ACTION_LONG_PRESS]
     */
    fun onIconAction(actionType: Int = ACTION_TAP) {
        val now = SystemClock.elapsedRealtime()
        when (actionType) {
            ACTION_TAP -> {
                if (tapCount == 1 && now - lastTapTime <= DOUBLE_TAP_MS) {
                    tapCount = 0
                    resetJob?.cancel()
                    _detected.value = true
                } else {
                    tapCount = 1
                    lastTapTime = now
                    scheduleReset()
                }
            }
            ACTION_LONG_PRESS -> {
                tapCount = 0
                _detected.value = true
            }
        }
    }

    /** 读取并清除唤醒事件。 */
    fun consume(): Boolean {
        if (!_detected.value) return false
        _detected.value = false
        return true
    }

    private fun scheduleReset() {
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(DOUBLE_TAP_MS + 50)
            if (SystemClock.elapsedRealtime() - lastTapTime > DOUBLE_TAP_MS) {
                tapCount = 0
            }
        }
    }

    private fun createShortcut() {
        val shortcut = android.content.pm.ShortcutInfo.Builder(context, SHORTCUT_ID)
            .setShortLabel("JARVIS 唤醒")
            .setLongLabel("长按唤醒 JARVIS")
            .setIcon(android.graphics.drawable.Icon.createWithResource(context, com.jarvis.pineapple.R.drawable.ic_jarvis))
            .setIntent(Intent(context, com.jarvis.pineapple.ui.MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra(EXTRA_LONG_PRESS_WAKE, true)
            })
            .build()
        runCatching { (context.getSystemService(Context.SHORTCUT_SERVICE) as ShortcutManager).setDynamicShortcuts(listOf(shortcut)) }
    }

    companion object {
        const val ACTION_TAP = 0
        const val ACTION_LONG_PRESS = 1
        const val DOUBLE_TAP_MS = 600L
        const val EXTRA_LONG_PRESS_WAKE = "jarvis_wake_long_press"
        const val SHORTCUT_ID = "jarvis_wake"
    }
}

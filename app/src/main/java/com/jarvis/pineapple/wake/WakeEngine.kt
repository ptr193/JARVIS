package com.jarvis.pineapple.wake

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 唤醒来源
 */
enum class WakeSource {
    VOICE, POWER_BUTTON, NOTIFICATION, SHAKE, BLUETOOTH, TILE, APP_ICON, HOME_LONG_PRESS
}

/**
 * 唤醒事件
 */
data class WakeEvent(val source: WakeSource, val timestamp: Long = System.currentTimeMillis())

/**
 * 唤醒引擎：统一管理 8 种唤醒方式，分发唤醒事件。
 */
class WakeEngine(private val context: Context) {

    private val _wakeEvents = MutableSharedFlow<WakeEvent>(extraBufferCapacity = 16)
    val wakeEvents: SharedFlow<WakeEvent> = _wakeEvents.asSharedFlow()

    private val enabledSources = mutableSetOf(
        WakeSource.VOICE, WakeSource.POWER_BUTTON, WakeSource.NOTIFICATION,
        WakeSource.SHAKE, WakeSource.BLUETOOTH, WakeSource.TILE,
        WakeSource.APP_ICON, WakeSource.HOME_LONG_PRESS
    )

    /**
     * 触发唤醒。任一唤醒方式调用此方法。
     */
    fun trigger(source: WakeSource) {
        if (source in enabledSources) {
            _wakeEvents.tryEmit(WakeEvent(source))
        }
    }

    fun setEnabled(source: WakeSource, enabled: Boolean) {
        if (enabled) enabledSources.add(source) else enabledSources.remove(source)
    }

    fun isEnabled(source: WakeSource): Boolean = source in enabledSources
}

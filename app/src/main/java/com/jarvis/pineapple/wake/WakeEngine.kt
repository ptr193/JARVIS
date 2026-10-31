package com.jarvis.pineapple.wake

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val _unavailableReasons = MutableStateFlow<List<String>>(emptyList())
    val unavailableReasons: StateFlow<List<String>> = _unavailableReasons.asStateFlow()

    private val enabledSources = mutableSetOf(
        WakeSource.VOICE, WakeSource.POWER_BUTTON, WakeSource.NOTIFICATION,
        WakeSource.SHAKE, WakeSource.BLUETOOTH, WakeSource.TILE,
        WakeSource.APP_ICON, WakeSource.HOME_LONG_PRESS
    )

    // 8 种唤醒器实例
    private val voiceWakeDetector = VoiceWakeDetector(context)
    private val powerButtonWakeDetector = PowerButtonWakeDetector(context)
    private val notificationWakeDetector = NotificationWakeDetector(context)
    private val shakeWakeDetector = ShakeWakeDetector(context)
    private val bluetoothWakeDetector = BluetoothWakeDetector(context)
    private val tileWakeDetector = TileWakeDetector.getInstance()
    private val appIconWakeDetector = AppIconWakeDetector(context)
    private val homeLongPressDetector = HomeLongPressDetector(context)

    // 唤醒器初始化（传感器/广播/麦克风注册）与流收集均不需要主线程。
    // 使用 Default 调度器可避免在冷启动时与 Compose 首帧争抢主线程
    // （实机日志显示：WakeEngine.start 的类校验在主线程上耗时 7.8 秒，
    //  直接导致 KeepAliveService 的前台服务 5 秒超时崩溃）。
    // _wakeEvents 为线程安全的 MutableSharedFlow，跨线程发射安全。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mainJob: Job? = null

    /**
     * 启动所有已启用的唤醒器，并把各检测器的 detected 信号汇总为统一唤醒事件。
     */
    fun start() {
        mainJob?.cancel()
        mainJob = scope.launch {
            val reasons = mutableListOf<String>()
            if (WakeSource.VOICE in enabledSources && !voiceWakeDetector.start()) {
                voiceWakeDetector.unavailableReason.value?.let { reasons.add(it) }
            }
            if (WakeSource.POWER_BUTTON in enabledSources) powerButtonWakeDetector.start()
            if (WakeSource.NOTIFICATION in enabledSources) notificationWakeDetector.start()
            if (WakeSource.SHAKE in enabledSources && !shakeWakeDetector.start()) {
                shakeWakeDetector.unavailableReason.value?.let { reasons.add(it) }
            }
            if (WakeSource.BLUETOOTH in enabledSources) bluetoothWakeDetector.start()
            if (WakeSource.TILE in enabledSources) { /* Tile 由 TileService 自动触发 */ }
            if (WakeSource.APP_ICON in enabledSources) appIconWakeDetector.start()
            if (WakeSource.HOME_LONG_PRESS in enabledSources) homeLongPressDetector.start()
            _unavailableReasons.value = reasons

            launch {
                voiceWakeDetector.detected.collect { detected ->
                    if (detected) { voiceWakeDetector.consumeDetection(); trigger(WakeSource.VOICE) }
                }
            }
            launch {
                notificationWakeDetector.detected.collect { detected ->
                    if (detected) { notificationWakeDetector.consume(); trigger(WakeSource.NOTIFICATION) }
                }
            }
            launch {
                tileWakeDetector.detected.collect { detected ->
                    if (detected) { tileWakeDetector.consume(); trigger(WakeSource.TILE) }
                }
            }
            launch {
                powerButtonWakeDetector.detected.collect { on ->
                    if (on) { powerButtonWakeDetector.consume(); trigger(WakeSource.POWER_BUTTON) }
                }
            }
            launch {
                shakeWakeDetector.detected.collect { detected ->
                    if (detected) { shakeWakeDetector.consume(); trigger(WakeSource.SHAKE) }
                }
            }
            launch {
                bluetoothWakeDetector.detected.collect { detected ->
                    if (detected) { bluetoothWakeDetector.clear(); trigger(WakeSource.BLUETOOTH) }
                }
            }
            launch {
                appIconWakeDetector.detected.collect { detected ->
                    if (detected) { appIconWakeDetector.consume(); trigger(WakeSource.APP_ICON) }
                }
            }
            launch {
                homeLongPressDetector.detected.collect { detected ->
                    if (detected) { homeLongPressDetector.consume(); trigger(WakeSource.HOME_LONG_PRESS) }
                }
            }
        }
    }

    fun stop() {
        mainJob?.cancel()
        mainJob = null
        voiceWakeDetector.stop()
        powerButtonWakeDetector.stop()
        notificationWakeDetector.stop()
        shakeWakeDetector.stop()
        bluetoothWakeDetector.stop()
        appIconWakeDetector.stop()
        homeLongPressDetector.stop()
    }

    /**
     * 触发唤醒。任一唤醒方式调用此方法。
     */
    fun trigger(source: WakeSource) {
        if (source in enabledSources) {
            _wakeEvents.tryEmit(WakeEvent(source))
        }
    }

    /** 语音唤醒检测回调 */
    fun onVoiceWakeDetected() { trigger(WakeSource.VOICE) }
    /** 电源键连按回调 */
    fun onPowerButtonPressed() { trigger(WakeSource.POWER_BUTTON) }
    /** 通知栏点击回调 */
    fun onNotificationClicked() { trigger(WakeSource.NOTIFICATION) }
    /** 摇一摇回调 */
    fun onShakeDetected() { trigger(WakeSource.SHAKE) }
    /** 蓝牙按键回调 */
    fun onBluetoothButtonPressed() { trigger(WakeSource.BLUETOOTH) }
    /** 磁贴点击回调 */
    fun onTileClicked() { trigger(WakeSource.TILE) }
    /** 应用图标双击/长按回调 */
    fun onAppIconAction(actionType: Int) {
        appIconWakeDetector.onIconAction(actionType)
        if (appIconWakeDetector.consume()) trigger(WakeSource.APP_ICON)
    }
    /** 长按 Home 键回调 */
    fun onHomeLongPress() {
        homeLongPressDetector.onHomeLongPress()
        homeLongPressDetector.consume()
        trigger(WakeSource.HOME_LONG_PRESS)
    }

    fun setEnabled(source: WakeSource, enabled: Boolean) {
        if (enabled) {
            enabledSources.add(source)
            when (source) {
                WakeSource.VOICE -> {
                    if (!voiceWakeDetector.start()) {
                        voiceWakeDetector.unavailableReason.value?.let { noteUnavailable(it) }
                    }
                }
                WakeSource.POWER_BUTTON -> powerButtonWakeDetector.start()
                WakeSource.NOTIFICATION -> notificationWakeDetector.start()
                WakeSource.SHAKE -> {
                    if (!shakeWakeDetector.start()) {
                        shakeWakeDetector.unavailableReason.value?.let { noteUnavailable(it) }
                    }
                }
                WakeSource.BLUETOOTH -> bluetoothWakeDetector.start()
                WakeSource.TILE -> Unit
                WakeSource.APP_ICON -> appIconWakeDetector.start()
                WakeSource.HOME_LONG_PRESS -> homeLongPressDetector.start()
            }
        } else {
            enabledSources.remove(source)
            when (source) {
                WakeSource.VOICE -> voiceWakeDetector.stop()
                WakeSource.POWER_BUTTON -> powerButtonWakeDetector.stop()
                WakeSource.NOTIFICATION -> notificationWakeDetector.stop()
                WakeSource.SHAKE -> shakeWakeDetector.stop()
                WakeSource.BLUETOOTH -> bluetoothWakeDetector.stop()
                WakeSource.TILE -> Unit
                WakeSource.APP_ICON -> appIconWakeDetector.stop()
                WakeSource.HOME_LONG_PRESS -> homeLongPressDetector.stop()
            }
        }
    }

    fun isEnabled(source: WakeSource): Boolean = source in enabledSources

    private fun noteUnavailable(reason: String) {
        if (reason.isBlank()) return
        val current = _unavailableReasons.value
        if (reason !in current) {
            _unavailableReasons.value = current + reason
        }
    }
}

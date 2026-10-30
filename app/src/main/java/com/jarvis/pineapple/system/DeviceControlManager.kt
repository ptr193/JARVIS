package com.jarvis.pineapple.system

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.jarvis.pineapple.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * 设备控制管理器（功能 #72-#99）。
 *
 * 功能：
 * - 灯光控制
 * - 温度控制
 * - 窗帘控制
 * - 安防管理
 * - 入侵警报
 * - 摄像头监控
 * - 家电联动
 * - 多设备群控
 * - 设备编队
 * - 设备分组管理
 * - 多设备统一控制
 * - 远程配置下发
 * - 远程协助与接管
 * - 场景自动化触发
 * - 功能模块按需启停
 * - 多设备协同组合
 * - 性能与功耗调度
 * - 电量分配管理
 * - 续航优化
 * - 芯片性能调度
 * - 设备散热与运行保障
 * - 硬件故障检测
 * - 故障诊断
 * - 问题诊断与根因分析
 * - 优化建议推送
 * - 维修方案生成
 * - 配置模板管理
 * - 版本迭代管理
 */
class DeviceControlManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 智能家居网关传输层（与 SmartHomeManager 共用配置）。 */
    private val transport = com.jarvis.pineapple.smarthome.DeviceTransport(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ==================== 灯光控制（#72） ====================

    /**
     * 灯光状态
     */
    data class LightState(
        val deviceId: String,
        val deviceName: String,
        val isOn: Boolean = false,
        val brightness: Int = 100,        // 0-100
        val colorTemperature: Int = 4000,  // 色温 K
        val color: Int = 0xFFFFFFFF.toInt()
    )

    private val _lightStates = MutableStateFlow<List<LightState>>(emptyList())
    val lightStates: StateFlow<List<LightState>> = _lightStates.asStateFlow()

    /**
     * 开灯
     */
    fun turnOnLight(deviceId: String) {
        updateLightState(deviceId) { it.copy(isOn = true) }
    }

    /**
     * 关灯
     */
    fun turnOffLight(deviceId: String) {
        updateLightState(deviceId) { it.copy(isOn = false) }
    }

    /**
     * 设置亮度
     */
    fun setLightBrightness(deviceId: String, brightness: Int) {
        updateLightState(deviceId) { it.copy(brightness = brightness.coerceIn(0, 100)) }
    }

    /**
     * 设置色温
     */
    fun setLightColorTemperature(deviceId: String, temperature: Int) {
        updateLightState(deviceId) { it.copy(colorTemperature = temperature.coerceIn(2700, 6500)) }
    }

    private fun updateLightState(deviceId: String, update: (LightState) -> LightState) {
        _lightStates.value = _lightStates.value.map { state ->
            if (state.deviceId == deviceId) update(state) else state
        }
    }

    // ==================== 温度控制（#73） ====================

    /**
     * 温度控制状态
     */
    data class TemperatureState(
        val deviceId: String,
        val deviceName: String,
        val currentTemperature: Float = 25f,
        val targetTemperature: Float = 25f,
        val mode: TemperatureMode = TemperatureMode.AUTO,
        val isOn: Boolean = false
    )

    enum class TemperatureMode {
        COOL, HEAT, AUTO, FAN_ONLY, DRY
    }

    private val _temperatureStates = MutableStateFlow<List<TemperatureState>>(emptyList())
    val temperatureStates: StateFlow<List<TemperatureState>> = _temperatureStates.asStateFlow()

    /**
     * 设置目标温度
     */
    fun setTargetTemperature(deviceId: String, temperature: Float) {
        _temperatureStates.value = _temperatureStates.value.map { state ->
            if (state.deviceId == deviceId) {
                state.copy(targetTemperature = temperature.coerceIn(16f, 30f), isOn = true)
            } else state
        }
    }

    /**
     * 设置模式
     */
    fun setTemperatureMode(deviceId: String, mode: TemperatureMode) {
        _temperatureStates.value = _temperatureStates.value.map { state ->
            if (state.deviceId == deviceId) state.copy(mode = mode) else state
        }
    }

    // ==================== 窗帘控制（#74） ====================

    /**
     * 窗帘状态
     */
    data class CurtainState(
        val deviceId: String,
        val deviceName: String,
        val position: Int = 0,  // 0=关闭, 100=完全打开
        val isOpen: Boolean = false
    )

    private val _curtainStates = MutableStateFlow<List<CurtainState>>(emptyList())
    val curtainStates: StateFlow<List<CurtainState>> = _curtainStates.asStateFlow()

    /**
     * 打开窗帘
     */
    fun openCurtain(deviceId: String) {
        _curtainStates.value = _curtainStates.value.map { state ->
            if (state.deviceId == deviceId) state.copy(position = 100, isOpen = true) else state
        }
    }

    /**
     * 关闭窗帘
     */
    fun closeCurtain(deviceId: String) {
        _curtainStates.value = _curtainStates.value.map { state ->
            if (state.deviceId == deviceId) state.copy(position = 0, isOpen = false) else state
        }
    }

    /**
     * 设置窗帘位置
     */
    fun setCurtainPosition(deviceId: String, position: Int) {
        _curtainStates.value = _curtainStates.value.map { state ->
            if (state.deviceId == deviceId) {
                state.copy(position = position.coerceIn(0, 100), isOpen = position > 0)
            } else state
        }
    }

    // ==================== 安防管理（#75） ====================

    /**
     * 安防状态
     */
    data class SecurityState(
        val isArmed: Boolean = false,
        val mode: SecurityMode = SecurityMode.DISARMED,
        val sensors: List<SensorState> = emptyList(),
        val lastArmedTime: Long = 0,
        val lastDisarmedTime: Long = 0
    )

    enum class SecurityMode {
        DISARMED, HOME, AWAY, NIGHT
    }

    data class SensorState(
        val sensorId: String,
        val sensorType: SensorType,
        val isTriggered: Boolean = false,
        val batteryLevel: Int = 100
    )

    enum class SensorType {
        DOOR, WINDOW, MOTION, SMOKE, GAS, GLASS_BREAK
    }

    private val _securityState = MutableStateFlow(SecurityState())
    val securityState: StateFlow<SecurityState> = _securityState.asStateFlow()

    /**
     * 布防
     */
    fun armSecurity(mode: SecurityMode) {
        _securityState.value = _securityState.value.copy(
            isArmed = true,
            mode = mode,
            lastArmedTime = System.currentTimeMillis()
        )
    }

    /**
     * 撤防
     */
    fun disarmSecurity() {
        _securityState.value = _securityState.value.copy(
            isArmed = false,
            mode = SecurityMode.DISARMED,
            lastDisarmedTime = System.currentTimeMillis()
        )
    }

    // ==================== 入侵警报（#76） ====================

    /**
     * 发送入侵警报
     */
    fun triggerIntrusionAlert(sensorId: String, sensorType: SensorType) {
        // 更新传感器状态
        _securityState.value = _securityState.value.copy(
            sensors = _securityState.value.sensors.map { sensor ->
                if (sensor.sensorId == sensorId) sensor.copy(isTriggered = true) else sensor
            }
        )

        // 1) 弹出高优先级通知
        val label = sensorLabel(sensorType)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "安防警报", NotificationManager.IMPORTANCE_HIGH)
        )
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("JARVIS 入侵警报")
            .setContentText("$label 传感器（$sensorId）触发")
            .setStyle(NotificationCompat.BigTextStyle().bigText("检测到 $label 传感器 $sensorId 触发，请立即查看。"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(ALERT_NOTIFICATION_ID, notification) }

        // 2) 播放警报音
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            RingtoneManager.getRingtone(context, uri)?.play()
        }.onFailure { Log.w(TAG, "播放警报音失败: ${it.message}") }

        // 3) 向网关下发警报事件
        scope.launch {
            val ok = transport.sendCommand(sensorId, "alert", mapOf("type" to sensorType.name))
            if (!ok) Log.d(TAG, "警报未下发（未配置网关）: $sensorId")
        }
    }

    private fun sensorLabel(type: SensorType): String = when (type) {
        SensorType.DOOR -> "门磁"
        SensorType.WINDOW -> "窗磁"
        SensorType.MOTION -> "人体移动"
        SensorType.SMOKE -> "烟雾"
        SensorType.GAS -> "燃气"
        SensorType.GLASS_BREAK -> "玻璃破碎"
    }

    // ==================== 摄像头监控（#77） ====================

    /**
     * 摄像头状态
     */
    data class CameraState(
        val cameraId: String,
        val cameraName: String,
        val isOnline: Boolean = false,
        val isRecording: Boolean = false,
        val streamUrl: String = ""
    )

    private val _cameraStates = MutableStateFlow<List<CameraState>>(emptyList())
    val cameraStates: StateFlow<List<CameraState>> = _cameraStates.asStateFlow()

    /**
     * 开始录制
     */
    fun startRecording(cameraId: String) {
        _cameraStates.value = _cameraStates.value.map { camera ->
            if (camera.cameraId == cameraId) camera.copy(isRecording = true) else camera
        }
    }

    /**
     * 停止录制
     */
    fun stopRecording(cameraId: String) {
        _cameraStates.value = _cameraStates.value.map { camera ->
            if (camera.cameraId == cameraId) camera.copy(isRecording = false) else camera
        }
    }

    // ==================== 家电联动（#78） ====================

    /**
     * 家电状态
     */
    data class ApplianceState(
        val deviceId: String,
        val deviceName: String,
        val deviceType: ApplianceType,
        val isOn: Boolean = false,
        val powerConsumption: Float = 0f
    )

    enum class ApplianceType {
        TV, WASHER, REFRIGERATOR, AIR_CONDITIONER, FAN, HEATER, HUMIDIFIER, AIR_PURIFIER
    }

    private val _applianceStates = MutableStateFlow<List<ApplianceState>>(emptyList())
    val applianceStates: StateFlow<List<ApplianceState>> = _applianceStates.asStateFlow()

    /**
     * 打开家电
     */
    fun turnOnAppliance(deviceId: String) {
        _applianceStates.value = _applianceStates.value.map { appliance ->
            if (appliance.deviceId == deviceId) appliance.copy(isOn = true) else appliance
        }
    }

    /**
     * 关闭家电
     */
    fun turnOffAppliance(deviceId: String) {
        _applianceStates.value = _applianceStates.value.map { appliance ->
            if (appliance.deviceId == deviceId) appliance.copy(isOn = false) else appliance
        }
    }

    // ==================== 多设备群控（#79） ====================

    /**
     * 批量控制设备
     */
    fun batchControl(deviceIds: List<String>, action: DeviceAction) {
        when (action) {
            is DeviceAction.TurnOn -> {
                deviceIds.forEach { turnOnDevice(it) }
            }
            is DeviceAction.TurnOff -> {
                deviceIds.forEach { turnOffDevice(it) }
            }
            is DeviceAction.SetBrightness -> {
                deviceIds.forEach { setDeviceBrightness(it, action.value) }
            }
            is DeviceAction.SetTemperature -> {
                deviceIds.forEach { setDeviceTemperature(it, action.value) }
            }
        }
    }

    sealed class DeviceAction {
        object TurnOn : DeviceAction()
        object TurnOff : DeviceAction()
        data class SetBrightness(val value: Int) : DeviceAction()
        data class SetTemperature(val value: Float) : DeviceAction()
    }

    private fun turnOnDevice(deviceId: String) {
        updateLightState(deviceId) { it.copy(isOn = true) }
        _temperatureStates.value = _temperatureStates.value.map {
            if (it.deviceId == deviceId) it.copy(isOn = true) else it
        }
        _curtainStates.value = _curtainStates.value.map {
            if (it.deviceId == deviceId) it.copy(isOpen = true, position = 100) else it
        }
        _applianceStates.value = _applianceStates.value.map {
            if (it.deviceId == deviceId) it.copy(isOn = true) else it
        }
    }

    private fun turnOffDevice(deviceId: String) {
        updateLightState(deviceId) { it.copy(isOn = false) }
        _temperatureStates.value = _temperatureStates.value.map {
            if (it.deviceId == deviceId) it.copy(isOn = false) else it
        }
        _curtainStates.value = _curtainStates.value.map {
            if (it.deviceId == deviceId) it.copy(position = 0, isOpen = false) else it
        }
        _applianceStates.value = _applianceStates.value.map {
            if (it.deviceId == deviceId) it.copy(isOn = false) else it
        }
    }

    private fun setDeviceBrightness(deviceId: String, brightness: Int) {
        updateLightState(deviceId) { it.copy(brightness = brightness.coerceIn(0, 100), isOn = brightness > 0) }
    }

    private fun setDeviceTemperature(deviceId: String, temperature: Float) {
        _temperatureStates.value = _temperatureStates.value.map {
            if (it.deviceId == deviceId) it.copy(targetTemperature = temperature, isOn = true) else it
        }
    }

    // ==================== 设备编队（#80） ====================

    /**
     * 设备编队
     */
    data class DeviceFormation(
        val id: String,
        val name: String,
        val deviceIds: List<String>,
        val formationType: FormationType
    )

    enum class FormationType {
        LIGHTING, CLIMATE, SECURITY, ENTERTAINMENT, CUSTOM
    }

    private val _formations = MutableStateFlow<List<DeviceFormation>>(emptyList())
    val formations: StateFlow<List<DeviceFormation>> = _formations.asStateFlow()

    /**
     * 创建编队
     */
    fun createFormation(name: String, deviceIds: List<String>, type: FormationType): DeviceFormation {
        val formation = DeviceFormation(
            id = "formation_${System.currentTimeMillis()}",
            name = name,
            deviceIds = deviceIds,
            formationType = type
        )
        val current = _formations.value.toMutableList()
        current.add(formation)
        _formations.value = current
        return formation
    }

    /**
     * 执行编队
     */
    fun executeFormation(formationId: String, action: DeviceAction) {
        val formation = _formations.value.firstOrNull { it.id == formationId } ?: return
        batchControl(formation.deviceIds, action)
    }

    // ==================== 设备分组管理（#81） ====================

    /**
     * 设备分组
     */
    data class DeviceGroup(
        val id: String,
        val name: String,
        val deviceIds: List<String>,
        val groupType: GroupType
    )

    enum class GroupType {
        ROOM, FLOOR, FUNCTION, CUSTOM
    }

    private val _deviceGroups = MutableStateFlow<List<DeviceGroup>>(emptyList())
    val deviceGroups: StateFlow<List<DeviceGroup>> = _deviceGroups.asStateFlow()

    /**
     * 创建分组
     */
    fun createGroup(name: String, deviceIds: List<String>, type: GroupType): DeviceGroup {
        val group = DeviceGroup(
            id = "group_${System.currentTimeMillis()}",
            name = name,
            deviceIds = deviceIds,
            groupType = type
        )
        val current = _deviceGroups.value.toMutableList()
        current.add(group)
        _deviceGroups.value = current
        return group
    }

    /**
     * 控制分组
     */
    fun controlGroup(groupId: String, action: DeviceAction) {
        val group = _deviceGroups.value.firstOrNull { it.id == groupId } ?: return
        batchControl(group.deviceIds, action)
    }

    // ==================== 多设备统一控制（#82） ====================

    /**
     * 统一控制多个设备
     */
    fun unifiedControl(controls: List<UnifiedControl>) {
        controls.forEach { control ->
            when (control.action) {
                is DeviceAction.TurnOn -> turnOnDevice(control.deviceId)
                is DeviceAction.TurnOff -> turnOffDevice(control.deviceId)
                is DeviceAction.SetBrightness -> setDeviceBrightness(control.deviceId, control.action.value)
                is DeviceAction.SetTemperature -> setDeviceTemperature(control.deviceId, control.action.value)
            }
        }
    }

    data class UnifiedControl(
        val deviceId: String,
        val deviceName: String,
        val action: DeviceAction
    )

    // ==================== 远程配置下发（#83） ====================

    /**
     * 远程配置
     */
    data class RemoteConfig(
        val configId: String,
        val configType: String,
        val parameters: Map<String, Any>,
        val targetDevices: List<String>,
        val priority: Int = 5
    )

    /**
     * 下发配置
     */
    fun deployConfig(config: RemoteConfig): Boolean {
        if (config.targetDevices.isEmpty()) return false
        scope.launch {
            config.targetDevices.forEach { deviceId ->
                transport.sendCommand(
                    deviceId,
                    "apply_config",
                    mapOf(
                        "configId" to config.configId,
                        "configType" to config.configType,
                        "priority" to config.priority,
                        "parameters" to config.parameters
                    )
                )
            }
            Log.i(TAG, "远程配置 ${config.configId} 已下发至 ${config.targetDevices.size} 台设备")
        }
        // 配置已交由传输层异步下发；返回网关是否可用（未配置时明确返回 false）
        return transport.isConfigured
    }

    // ==================== 远程协助与接管（#84） ====================

    /**
     * 远程会话
     */
    data class RemoteSession(
        val sessionId: String,
        val operatorId: String,
        val targetDeviceId: String,
        val status: SessionStatus = SessionStatus.PENDING,
        val startTime: Long = 0
    )

    enum class SessionStatus {
        PENDING, ACTIVE, COMPLETED, FAILED
    }

    /**
     * 发起远程协助
     */
    fun initiateRemoteAssistance(deviceId: String, operatorId: String): RemoteSession {
        return RemoteSession(
            sessionId = "remote_${System.currentTimeMillis()}",
            operatorId = operatorId,
            targetDeviceId = deviceId,
            status = SessionStatus.ACTIVE,
            startTime = System.currentTimeMillis()
        )
    }

    // ==================== 场景自动化触发（#85） ====================

    /**
     * 自动化场景
     */
    data class AutomationScene(
        val id: String,
        val name: String,
        val triggers: List<SceneTrigger>,
        val actions: List<SceneAction>,
        val isEnabled: Boolean = true
    )

    sealed class SceneTrigger {
        data class TimeTrigger(val hour: Int, val minute: Int) : SceneTrigger()
        data class DeviceTrigger(val deviceId: String, val condition: String) : SceneTrigger()
        data class LocationTrigger(val latitude: Double, val longitude: Double, val radius: Float) : SceneTrigger()
        data class SensorTrigger(val sensorId: String, val threshold: Float) : SceneTrigger()
    }

    sealed class SceneAction {
        data class DeviceControlAction(val deviceId: String, val action: DeviceAction) : SceneAction()
        data class DelayAction(val delayMs: Long) : SceneAction()
        data class NotifyAction(val message: String) : SceneAction()
        data class SubSceneAction(val sceneId: String) : SceneAction()
    }

    private val _automationScenes = MutableStateFlow<List<AutomationScene>>(emptyList())
    val automationScenes: StateFlow<List<AutomationScene>> = _automationScenes.asStateFlow()

    /**
     * 创建自动化场景
     */
    fun createScene(name: String, triggers: List<SceneTrigger>, actions: List<SceneAction>): AutomationScene {
        val scene = AutomationScene(
            id = "scene_${System.currentTimeMillis()}",
            name = name,
            triggers = triggers,
            actions = actions
        )
        val current = _automationScenes.value.toMutableList()
        current.add(scene)
        _automationScenes.value = current
        return scene
    }

    /**
     * 触发场景
     */
    fun triggerScene(sceneId: String) {
        val scene = _automationScenes.value.firstOrNull { it.id == sceneId } ?: return
        if (!scene.isEnabled) return

        // 执行场景动作
        scene.actions.forEach { action ->
            executeSceneAction(action)
        }
    }

    private fun executeSceneAction(action: SceneAction) {
        when (action) {
            is SceneAction.DeviceControlAction -> {
                // 执行设备动作
                controlDevice(action.deviceId, action.action)
            }
            is SceneAction.DelayAction -> {
                // 延迟（场景动作在协程中顺序执行）
                try {
                    Thread.sleep(action.delayMs.coerceIn(0L, 60_000L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            is SceneAction.NotifyAction -> {
                // 发送本地通知
                sendSceneNotification(action.message)
            }
            is SceneAction.SubSceneAction -> {
                // 触发另一个场景
                triggerScene(action.sceneId)
            }
        }
    }

    /** 在当前设备上执行一个设备动作（供场景自动化复用）。 */
    private fun controlDevice(deviceId: String, action: DeviceAction) {
        when (action) {
            is DeviceAction.TurnOn -> turnOnDevice(deviceId)
            is DeviceAction.TurnOff -> turnOffDevice(deviceId)
            is DeviceAction.SetBrightness -> setDeviceBrightness(deviceId, action.value)
            is DeviceAction.SetTemperature -> setDeviceTemperature(deviceId, action.value)
        }
    }

    /** 发送场景通知。 */
    private fun sendSceneNotification(message: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    SCENE_CHANNEL_ID,
                    "场景自动化",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
                manager.createNotificationChannel(channel)
            }
            val notification = androidx.core.app.NotificationCompat.Builder(context, SCENE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("JARVIS 场景自动化")
                .setContentText(message)
                .setAutoCancel(true)
                .build()
            manager.notify(message.hashCode(), notification)
        } catch (e: Exception) {
            Log.w(TAG, "发送场景通知失败", e)
        }
    }

    // ==================== 功能模块按需启停（#86） ====================

    /**
     * 功能模块状态
     */
    data class ModuleState(
        val moduleId: String,
        val moduleName: String,
        val isEnabled: Boolean = true,
        val isRunning: Boolean = false
    )

    private val _moduleStates = MutableStateFlow<List<ModuleState>>(emptyList())
    val moduleStates: StateFlow<List<ModuleState>> = _moduleStates.asStateFlow()

    /**
     * 启用模块
     */
    fun enableModule(moduleId: String) {
        _moduleStates.value = _moduleStates.value.map { module ->
            if (module.moduleId == moduleId) module.copy(isEnabled = true) else module
        }
    }

    /**
     * 禁用模块
     */
    fun disableModule(moduleId: String) {
        _moduleStates.value = _moduleStates.value.map { module ->
            if (module.moduleId == moduleId) module.copy(isEnabled = false, isRunning = false) else module
        }
    }

    // ==================== 多设备协同组合（#87） ====================

    /**
     * 协同任务
     */
    data class CollaborationTask(
        val id: String,
        val name: String,
        val steps: List<CollaborationStep>,
        val isExecuting: Boolean = false
    )

    data class CollaborationStep(
        val stepNumber: Int,
        val deviceId: String,
        val action: DeviceAction,
        val delayAfterMs: Long = 0
    )

    /**
     * 创建协同任务
     */
    fun createCollaborationTask(name: String, steps: List<CollaborationStep>): CollaborationTask {
        return CollaborationTask(
            id = "collab_${System.currentTimeMillis()}",
            name = name,
            steps = steps
        )
    }

    /**
     * 执行协同任务
     */
    suspend fun executeCollaborationTask(task: CollaborationTask) {
        for (step in task.steps) {
            // 执行步骤
            when (step.action) {
                is DeviceAction.TurnOn -> turnOnDevice(step.deviceId)
                is DeviceAction.TurnOff -> turnOffDevice(step.deviceId)
                is DeviceAction.SetBrightness -> setDeviceBrightness(step.deviceId, step.action.value)
                is DeviceAction.SetTemperature -> setDeviceTemperature(step.deviceId, step.action.value)
            }

            // 延迟
            if (step.delayAfterMs > 0) {
                kotlinx.coroutines.delay(step.delayAfterMs)
            }
        }
    }

    // ==================== 性能与功耗调度（#88） ====================

    /**
     * 性能模式
     */
    enum class PerformanceMode {
        POWER_SAVER, BALANCED, PERFORMANCE, HIGH_PERFORMANCE
    }

    private val _performanceMode = MutableStateFlow(PerformanceMode.BALANCED)
    val performanceMode: StateFlow<PerformanceMode> = _performanceMode.asStateFlow()

    /**
     * 设置性能模式
     */
    fun setPerformanceMode(mode: PerformanceMode) {
        _performanceMode.value = mode
        applyPerformanceMode(mode)
    }

    private fun applyPerformanceMode(mode: PerformanceMode) {
        val applied = mutableListOf<String>()
        val scale = animationScale(mode)
        if (applySettingInt(Settings.System.SCREEN_OFF_TIMEOUT, screenOffTimeout(mode))) {
            applied += "screen_off_timeout=${screenOffTimeout(mode)}"
        }
        if (applySettingFloat(Settings.System.WINDOW_ANIMATION_SCALE, scale)) {
            applySettingFloat(Settings.System.TRANSITION_ANIMATION_SCALE, scale)
            applySettingFloat(Settings.System.ANIMATOR_DURATION_SCALE, scale)
            applied += "animation_scale=$scale"
        }
        if (applied.isEmpty()) {
            Log.i(TAG, "性能模式 ${mode.name} 已记录；未授予 WRITE_SETTINGS，系统参数未修改")
        } else {
            Log.i(TAG, "性能模式 ${mode.name} 已应用: ${applied.joinToString()}")
        }
    }

    private fun animationScale(mode: PerformanceMode): Float = when (mode) {
        PerformanceMode.POWER_SAVER -> 0.5f
        PerformanceMode.BALANCED -> 1.0f
        PerformanceMode.PERFORMANCE -> 1.0f
        PerformanceMode.HIGH_PERFORMANCE -> 1.5f
    }

    private fun screenOffTimeout(mode: PerformanceMode): Int = when (mode) {
        PerformanceMode.POWER_SAVER -> 30_000
        PerformanceMode.BALANCED -> 60_000
        PerformanceMode.PERFORMANCE -> 120_000
        PerformanceMode.HIGH_PERFORMANCE -> 300_000
    }

    private fun applySettingInt(key: String, value: Int): Boolean =
        runCatching { Settings.System.putInt(context.contentResolver, key, value) }.getOrDefault(false)

    private fun applySettingFloat(key: String, value: Float): Boolean =
        runCatching { Settings.System.putFloat(context.contentResolver, key, value) }.getOrDefault(false)

    // ==================== 电量分配管理（#89） ====================

    /**
     * 电量分配
     */
    data class PowerAllocation(
        val component: String,
        val allocatedPower: Float,
        val priority: Int
    )

    /**
     * 获取电量分配建议
     */
    fun getPowerAllocationSuggestions(batteryLevel: Int): List<PowerAllocation> {
        val level = batteryLevel.coerceIn(0, 100)
        // 依据剩余电量动态分配各子系统功率占比（总和恒为 100%）
        val display = 10f + level * 0.25f
        val cpu = 15f + level * 0.30f
        val network = 5f + level * 0.15f
        val background = (100f - display - cpu - network).coerceAtLeast(0f)
        val low = level <= 20
        return listOf(
            PowerAllocation("display", display, if (low) 1 else 1),
            PowerAllocation("cpu", cpu, if (low) 3 else 2),
            PowerAllocation("network", network, if (low) 4 else 3),
            PowerAllocation("background", background, if (low) 5 else 4)
        )
    }

    // ==================== 续航优化（#90） ====================

    /**
     * 续航优化建议
     */
    data class BatteryOptimizationSuggestion(
        val type: OptimizationType,
        val description: String,
        val estimatedSavingMinutes: Int,
        val isApplicable: Boolean
    )

    enum class OptimizationType {
        REDUCE_BRIGHTness, CLOSE_BACKGROUND, REDUCE_SYNC, LIMIT_LOCATION, REDUCE_ANIMATIONS
    }

    /**
     * 获取续航优化建议
     */
    fun getBatteryOptimizationSuggestions(): List<BatteryOptimizationSuggestion> {
        val suggestions = mutableListOf<BatteryOptimizationSuggestion>()

        // 屏幕亮度：基于真实系统设置计算可节省时长
        val brightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        val brightnessPercent = (brightness * 100 / 255).coerceIn(0, 100)
        val brightnessSaving = ((brightnessPercent - 30).coerceAtLeast(0) * 60 / 100)
        suggestions += BatteryOptimizationSuggestion(
            type = OptimizationType.REDUCE_BRIGHTness,
            description = "当前屏幕亮度 $brightnessPercent%，调低至 30% 预计可延长续航约 $brightnessSaving 分钟",
            estimatedSavingMinutes = brightnessSaving,
            isApplicable = brightnessPercent > 40
        )

        // 屏幕超时：过长则建议缩短
        val timeoutMs = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        }.getOrDefault(60_000)
        val timeoutMinutes = timeoutMs / 60_000
        suggestions += BatteryOptimizationSuggestion(
            type = OptimizationType.CLOSE_BACKGROUND,
            description = "屏幕超时为 $timeoutMinutes 分钟，缩短至 1 分钟可减少待机耗电",
            estimatedSavingMinutes = ((timeoutMinutes - 1).coerceAtLeast(0) * 3),
            isApplicable = timeoutMinutes > 2
        )

        // 内存压力：占用偏高时建议减少后台
        val memory = sampleMemoryUsage()
        suggestions += BatteryOptimizationSuggestion(
            type = OptimizationType.REDUCE_SYNC,
            description = "内存占用 %.0f%%，限制后台同步可降低唤醒频率".format(memory),
            estimatedSavingMinutes = if (memory > 75f) 20 else 5,
            isApplicable = memory > 60f
        )

        // 温度：偏高时建议限制性能
        val temperature = sampleTemperature()
        suggestions += BatteryOptimizationSuggestion(
            type = OptimizationType.REDUCE_ANIMATIONS,
            description = "设备温度 %.1f°C".format(temperature) + if (temperature > 40f) "，建议关闭动画以降温省电" else "，温度正常",
            estimatedSavingMinutes = if (temperature > 40f) 10 else 0,
            isApplicable = temperature > 40f
        )

        return suggestions
    }

    // ==================== 芯片性能调度（#91） ====================

    /**
     * 芯片性能状态
     */
    data class ChipPerformanceState(
        val cpuUsage: Float = 0f,
        val gpuUsage: Float = 0f,
        val memoryUsage: Float = 0f,
        val temperature: Float = 0f,
        val throttling: Boolean = false
    )

    private val _chipState = MutableStateFlow(ChipPerformanceState())
    val chipState: StateFlow<ChipPerformanceState> = _chipState.asStateFlow()

    /**
     * 更新芯片状态（外部注入）。
     */
    fun updateChipState(state: ChipPerformanceState) {
        _chipState.value = state
    }

    @Volatile
    private var lastCpuTotal: Long = 0
    @Volatile
    private var lastCpuIdle: Long = 0

    /**
     * 采样本机芯片状态：读取 /proc/stat、/proc/meminfo 与热区温度。
     *
     * @return 真实采样结果
     */
    fun sampleChipState(): ChipPerformanceState {
        val cpu = sampleCpuUsage()
        val memory = sampleMemoryUsage()
        val temperature = sampleTemperature()
        val throttling = temperature > 0f && temperature >= 45f
        val state = ChipPerformanceState(
            cpuUsage = cpu,
            gpuUsage = 0f, // Android 未提供通用 GPU 占用接口
            memoryUsage = memory,
            temperature = temperature,
            throttling = throttling
        )
        _chipState.value = state
        return state
    }

    private fun sampleCpuUsage(): Float {
        return try {
            RandomAccessFile("/proc/stat", "r").use { reader ->
                val line = reader.readLine() ?: return 0f
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 5) return 0f
                val values = parts.drop(1).take(7).mapNotNull { it.toLongOrNull() }
                if (values.size < 4) return 0f
                val idle = values[3] + (values.getOrNull(4) ?: 0L)
                val total = values.sum()
                if (lastCpuTotal == 0L) {
                    lastCpuTotal = total
                    lastCpuIdle = idle
                    return 0f
                }
                val deltaTotal = total - lastCpuTotal
                val deltaIdle = idle - lastCpuIdle
                lastCpuTotal = total
                lastCpuIdle = idle
                if (deltaTotal <= 0) 0f
                else ((deltaTotal - deltaIdle).toDouble() / deltaTotal * 100.0).toFloat().coerceIn(0f, 100f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 /proc/stat 失败: ${e.message}")
            0f
        }
    }

    private fun sampleMemoryUsage(): Float {
        return try {
            var totalKb = 0L
            var availableKb = 0L
            File("/proc/meminfo").forEachLine { line ->
                when {
                    line.startsWith("MemTotal:") ->
                        totalKb = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
                    line.startsWith("MemAvailable:") ->
                        availableKb = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
                }
            }
            if (totalKb <= 0) 0f
            else ((totalKb - availableKb).toFloat() / totalKb * 100f).coerceIn(0f, 100f)
        } catch (e: Exception) {
            Log.w(TAG, "读取内存使用失败: ${e.message}")
            0f
        }
    }

    private fun sampleTemperature(): Float {
        return try {
            val dir = File("/sys/class/thermal")
            val zones = dir.listFiles { file -> file.name.startsWith("thermal_zone") } ?: return 0f
            var max = 0f
            zones.forEach { zone ->
                val tempFile = File(zone, "temp")
                if (tempFile.exists()) {
                    val raw = tempFile.readText().trim().toFloatOrNull() ?: return@forEach
                    val celsius = if (raw > 1000f) raw / 1000f else raw
                    if (celsius > max) max = celsius
                }
            }
            max
        } catch (e: Exception) {
            0f
        }
    }

    // ==================== 设备散热与运行保障（#92） ====================

    /**
     * 散热建议
     */
    fun getThermalSuggestions(temperature: Float): List<String> {
        return when {
            temperature > 45 -> listOf(
                "设备温度过高，建议关闭高负载应用",
                "建议移除手机壳以改善散热",
                "建议暂时让设备休息"
            )
            temperature > 40 -> listOf(
                "设备温度较高，建议降低屏幕亮度",
                "建议关闭不必要的后台应用"
            )
            else -> listOf("设备温度正常")
        }
    }

    // ==================== 硬件故障检测（#93） ====================

    /**
     * 硬件检测结果
     */
    data class HardwareDetectionResult(
        val component: String,
        val status: HardwareStatus,
        val details: String
    )

    enum class HardwareStatus {
        NORMAL, WARNING, CRITICAL, FAILED
    }

    /**
     * 检测硬件状态
     */
    fun detectHardwareIssues(): List<HardwareDetectionResult> {
        val results = mutableListOf<HardwareDetectionResult>()

        val cpu = sampleCpuUsage()
        results += HardwareDetectionResult(
            component = "CPU",
            status = statusFor(cpu, 70f, 90f),
            details = "占用率 %.1f%%".format(cpu)
        )

        val memory = sampleMemoryUsage()
        results += HardwareDetectionResult(
            component = "Memory",
            status = statusFor(memory, 80f, 92f),
            details = "占用率 %.1f%%".format(memory)
        )

        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalGb = stat.totalBytes / 1024.0 / 1024.0 / 1024.0
            val availableGb = stat.availableBytes / 1024.0 / 1024.0 / 1024.0
            val usedPercent = if (totalGb > 0) ((totalGb - availableGb) / totalGb * 100.0).toFloat() else 0f
            val status = when {
                availableGb < 0.5 -> HardwareStatus.CRITICAL
                usedPercent >= 90f -> HardwareStatus.WARNING
                else -> HardwareStatus.NORMAL
            }
            results += HardwareDetectionResult(
                component = "Storage",
                status = status,
                details = "可用 %.2f GB / 共 %.2f GB".format(availableGb, totalGb)
            )
        }.onFailure { results += HardwareDetectionResult("Storage", HardwareStatus.WARNING, "无法读取存储: ${it.message}") }

        runCatching {
            val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = battery.isCharging
            val status = when {
                level in 1..15 && !charging -> HardwareStatus.CRITICAL
                level in 16..30 && !charging -> HardwareStatus.WARNING
                else -> HardwareStatus.NORMAL
            }
            results += HardwareDetectionResult(
                component = "Battery",
                status = status,
                details = "电量 $level%" + if (charging) "（充电中）" else ""
            )
        }.onFailure { results += HardwareDetectionResult("Battery", HardwareStatus.WARNING, "无法读取电池: ${it.message}") }

        val temperature = sampleTemperature()
        if (temperature > 0f) {
            val status = when {
                temperature >= 50f -> HardwareStatus.CRITICAL
                temperature >= 43f -> HardwareStatus.WARNING
                else -> HardwareStatus.NORMAL
            }
            results += HardwareDetectionResult("Thermal", status, "温度 %.1f°C".format(temperature))
        }

        return results
    }

    private fun statusFor(percent: Float, warning: Float, critical: Float): HardwareStatus = when {
        percent >= critical -> HardwareStatus.CRITICAL
        percent >= warning -> HardwareStatus.WARNING
        else -> HardwareStatus.NORMAL
    }

    // ==================== 故障诊断（#94） ====================

    /**
     * 诊断结果
     */
    data class DiagnosisResult(
        val issue: String,
        val severity: DiagnosisSeverity,
        val possibleCauses: List<String>,
        val recommendedActions: List<String>
    )

    enum class DiagnosisSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    /**
     * 诊断问题
     */
    fun diagnoseIssue(symptoms: List<String>): DiagnosisResult {
        if (symptoms.isEmpty()) {
            return DiagnosisResult(
                issue = "未提供症状",
                severity = DiagnosisSeverity.LOW,
                possibleCauses = emptyList(),
                recommendedActions = listOf("请补充设备症状描述")
            )
        }
        val text = symptoms.joinToString(" ").lowercase()
        val causes = mutableListOf<String>()
        val actions = mutableListOf<String>()
        var severity = DiagnosisSeverity.LOW

        fun apply(next: DiagnosisSeverity) {
            if (next.ordinal > severity.ordinal) severity = next
        }

        if (text.contains("发烫") || text.contains("过热") || text.contains("温度")) {
            causes += "散热受阻或持续高负载运行"
            actions += "关闭高负载应用、移除手机壳并避免充电时使用"
            apply(DiagnosisSeverity.HIGH)
        }
        if (text.contains("卡顿") || text.contains("变慢") || text.contains("无响应")) {
            causes += "内存不足或后台进程过多"
            actions += "清理后台应用并重启设备"
            apply(DiagnosisSeverity.MEDIUM)
        }
        if (text.contains("自动重启") || text.contains("死机") || text.contains("崩溃")) {
            causes += "系统不稳定或存储故障"
            actions += "进入安全模式排查第三方应用，必要时备份后恢复出厂"
            apply(DiagnosisSeverity.HIGH)
        }
        if (text.contains("耗电") || text.contains("电池")) {
            causes += "后台唤醒过多或电池老化"
            actions += "查看耗电排行、限制后台活动并检测电池健康"
            apply(DiagnosisSeverity.MEDIUM)
        }
        if (text.contains("存储") || text.contains("空间不足")) {
            causes += "存储空间不足"
            actions += "清理缓存与大文件"
            apply(DiagnosisSeverity.MEDIUM)
        }
        if (text.contains("网络") || text.contains("断网") || text.contains("信号")) {
            causes += "网络配置异常或信号覆盖不足"
            actions += "切换飞行模式、重启网络并检查 APN 设置"
        }
        if (causes.isEmpty()) {
            causes += "未匹配到已知故障模式"
            actions += "运行完整硬件诊断以进一步定位"
        }
        return DiagnosisResult(
            issue = symptoms.joinToString("、"),
            severity = severity,
            possibleCauses = causes,
            recommendedActions = actions
        )
    }

    // ==================== 问题诊断与根因分析（#95） ====================
    /**
     * 根因分析结果
     */
    data class RootCauseAnalysisResult(
        val problem: String,
        val rootCause: String,
        val confidence: Float,
        val evidence: List<String>,
        val solution: String
    )

    /**
     * 分析根因
     */
    fun analyzeRootCause(problem: String, logs: List<String>): RootCauseAnalysisResult {
        val keywordCauses = linkedMapOf(
            "outofmemory" to "内存溢出",
            "anr" to "主线程阻塞",
            "nullpointer" to "空指针访问",
            "timeout" to "网络/IO 超时",
            "denied" to "权限不足",
            "no space" to "存储空间不足",
            "enospc" to "存储空间不足",
            "crash" to "进程崩溃",
            "securityexception" to "安全策略拦截",
            "connection refused" to "目标服务不可达"
        )
        val hits = mutableMapOf<String, Int>()
        val evidence = mutableListOf<String>()
        logs.forEach { line ->
            keywordCauses.forEach { (keyword, cause) ->
                if (line.contains(keyword, ignoreCase = true)) {
                    hits[cause] = (hits[cause] ?: 0) + 1
                    if (evidence.size < 8) evidence += line.take(200)
                }
            }
        }
        val rootCause = hits.maxByOrNull { it.value }?.key ?: "未在日志中发现明确根因"
        val confidence = if (logs.isEmpty()) 0f else (hits.values.sum().toFloat() / logs.size).coerceIn(0f, 1f)
        val solution = when {
            rootCause.contains("内存") -> "优化内存占用，避免一次性加载大量数据，必要时增大堆内存"
            rootCause.contains("主线程") -> "将耗时操作移出主线程，改用协程/工作线程"
            rootCause.contains("空指针") -> "为可能为空的引用增加判空与默认值处理"
            rootCause.contains("超时") -> "检查网络连通性并增大超时时间、增加重试"
            rootCause.contains("权限") -> "在运行时申请所需权限并处理拒绝分支"
            rootCause.contains("存储") -> "清理存储或引导用户释放空间"
            rootCause.contains("崩溃") -> "复现崩溃路径并检查最近一次改动"
            else -> "补充更多日志后重新分析"
        }
        return RootCauseAnalysisResult(
            problem = problem,
            rootCause = rootCause,
            confidence = confidence,
            evidence = evidence,
            solution = solution
        )
    }

    // ==================== 优化建议推送（#96） ====================

    /**
     * 推送优化建议
     */
    private val _optimizationSuggestions = MutableStateFlow<List<String>>(emptyList())
    val optimizationSuggestions: StateFlow<List<String>> = _optimizationSuggestions.asStateFlow()

    /**
     * 推送优化建议（#96）：记录建议并推送系统通知。
     */
    fun pushOptimizationSuggestions(suggestions: List<String>) {
        if (suggestions.isEmpty()) return
        _optimizationSuggestions.value = _optimizationSuggestions.value + suggestions

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            SUGGESTION_CHANNEL_ID,
            "优化建议",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "JARVIS 性能与设备优化建议" }
        manager.createNotificationChannel(channel)

        val body = suggestions.joinToString("\n") { "• $it" }
        val notification = NotificationCompat.Builder(context, SUGGESTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("JARVIS 优化建议")
            .setContentText(suggestions.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(SUGGESTION_NOTIFICATION_ID, notification)
    }

    // ==================== 维修方案生成（#97） ====================

    /**
     * 维修方案
     */
    data class RepairPlan(
        val issue: String,
        val severity: DiagnosisSeverity,
        val steps: List<String>,
        val estimatedCost: Float,
        val estimatedTime: String,
        val isDIY: Boolean
    )

    /**
     * 生成维修方案
     */
    fun generateRepairPlan(issue: String, severity: DiagnosisSeverity): RepairPlan {
        val steps = when (severity) {
            DiagnosisSeverity.LOW -> listOf(
                "1. 记录问题出现的频率与场景",
                "2. 重启相关应用或服务",
                "3. 观察是否复现"
            )
            DiagnosisSeverity.MEDIUM -> listOf(
                "1. 运行完整硬件诊断定位组件",
                "2. 清理缓存与后台进程",
                "3. 更新到最新系统版本",
                "4. 观察 24 小时"
            )
            DiagnosisSeverity.HIGH -> listOf(
                "1. 备份重要数据",
                "2. 进入安全模式排查第三方应用",
                "3. 检查存储与电池健康度",
                "4. 必要时恢复出厂设置"
            )
            DiagnosisSeverity.CRITICAL -> listOf(
                "1. 立即停止使用并备份数据",
                "2. 联系官方售后检测硬件",
                "3. 送修前解除账号锁与查找设备",
                "4. 携带购买凭证"
            )
        }
        val estimatedCost = when (severity) {
            DiagnosisSeverity.LOW -> 0f
            DiagnosisSeverity.MEDIUM -> 0f
            DiagnosisSeverity.HIGH -> 120f
            DiagnosisSeverity.CRITICAL -> 480f
        }
        val estimatedTime = when (severity) {
            DiagnosisSeverity.LOW -> "10 分钟"
            DiagnosisSeverity.MEDIUM -> "30 分钟"
            DiagnosisSeverity.HIGH -> "1-2 小时"
            DiagnosisSeverity.CRITICAL -> "1-3 天"
        }
        return RepairPlan(
            issue = issue,
            severity = severity,
            steps = steps,
            estimatedCost = estimatedCost,
            estimatedTime = estimatedTime,
            isDIY = severity.ordinal <= DiagnosisSeverity.MEDIUM.ordinal
        )
    }

    // ==================== 配置模板管理（#98） ====================

    /**
     * 配置模板
     */
    data class ConfigTemplate(
        val id: String,
        val name: String,
        val description: String,
        val settings: Map<String, Any>,
        val applicableDevices: List<String>
    )

    private val _configTemplates = MutableStateFlow<List<ConfigTemplate>>(emptyList())
    val configTemplates: StateFlow<List<ConfigTemplate>> = _configTemplates.asStateFlow()

    /**
     * 创建配置模板
     */
    fun createConfigTemplate(
        name: String,
        description: String,
        settings: Map<String, Any>,
        applicableDevices: List<String>
    ): ConfigTemplate {
        val template = ConfigTemplate(
            id = "template_${System.currentTimeMillis()}",
            name = name,
            description = description,
            settings = settings,
            applicableDevices = applicableDevices
        )
        val current = _configTemplates.value.toMutableList()
        current.add(template)
        _configTemplates.value = current
        return template
    }

    /**
     * 应用配置模板
     */
    fun applyConfigTemplate(templateId: String, targetDevices: List<String>): Boolean {
        val template = _configTemplates.value.firstOrNull { it.id == templateId } ?: return false
        val targets = if (targetDevices.isEmpty()) template.applicableDevices else targetDevices
        if (targets.isEmpty()) return false
        scope.launch {
            targets.forEach { deviceId ->
                transport.sendCommand(
                    deviceId,
                    "apply_template",
                    mapOf("templateId" to template.id, "settings" to template.settings)
                )
            }
            Log.i(TAG, "配置模板 ${template.name} 已下发至 ${targets.size} 台设备")
        }
        return transport.isConfigured
    }

    // ==================== 版本迭代管理（#99） ====================

    /**
     * 版本信息
     */
    data class VersionInfo(
        val currentVersion: String,
        val latestVersion: String,
        val updateAvailable: Boolean,
        val releaseNotes: String,
        val downloadUrl: String,
        val isMandatory: Boolean = false
    )

    /** 更新清单地址（由设置界面配置为自有服务器地址）。 */
    @Volatile var updateManifestUrl: String? = null

    /**
     * 检查更新（#98）：从配置的更新清单 URL 拉取版本信息并与当前版本对比。
     * 未配置 URL 时返回当前版本信息。
     */
    suspend fun checkForUpdates(): VersionInfo = withContext(Dispatchers.IO) {
        val current = currentVersionName()
        val url = updateManifestUrl
        if (url.isNullOrBlank()) {
            return@withContext VersionInfo(current, current, false, "", "")
        }
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val latest = json.optString("version", current)
            VersionInfo(
                currentVersion = current,
                latestVersion = latest,
                updateAvailable = compareVersions(latest, current) > 0,
                releaseNotes = json.optString("notes", ""),
                downloadUrl = json.optString("url", ""),
                isMandatory = json.optBoolean("mandatory", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "检查更新失败", e)
            VersionInfo(current, current, false, "", "")
        }
    }

    /**
     * 执行更新（#98）：下载 APK 并通过 FileProvider 触发系统安装器。
     */
    suspend fun performUpdate(versionInfo: VersionInfo): Boolean = withContext(Dispatchers.IO) {
        if (versionInfo.downloadUrl.isBlank()) return@withContext false
        try {
            val apk = File(context.cacheDir, "jarvis_update.apk")
            (URL(versionInfo.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 30000
            }.inputStream.use { input ->
                FileOutputStream(apk).use { output -> input.copyTo(output) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "执行更新失败", e)
            false
        }
    }

    private fun currentVersionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.1"
    } catch (e: Exception) {
        "0.0.1"
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    companion object {
        private const val TAG = "DeviceControlManager"
        private const val SUGGESTION_CHANNEL_ID = "jarvis_suggestions"
        private const val SUGGESTION_NOTIFICATION_ID = 0x4A41
        private const val SCENE_CHANNEL_ID = "jarvis_scenes"
        private const val ALERT_CHANNEL_ID = "jarvis_alerts"
        private const val ALERT_NOTIFICATION_ID = 0x4A42
    }
}

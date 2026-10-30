package com.jarvis.pineapple.smarthome

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 智能家居管理器（任务 6.1-6.4）。
 *
 * 功能：
 * - 智能家居适配层（Matter / HomeKit / Tuya / 米家）
 * - 设备发现与管理
 * - 设备控制
 * - 多设备协同
 */
class SmartHomeManager(private val context: Context) {


    /** 局域网/网关传输层：SSDP 发现 + HTTP 指令下发。 */
    private val transport = DeviceTransport(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ==================== 设备管理（6.2） ====================

    /**
     * 智能设备
     */
    data class SmartDevice(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val type: DeviceType,
        val protocol: ProtocolType,
        val manufacturer: String = "",
        val model: String = "",
        val room: String = "",
        val isOnline: Boolean = false,
        val isConnected: Boolean = false,
        val state: DeviceState = DeviceState(),
        val capabilities: List<DeviceCapability> = emptyList(),
        val lastSeen: Long = System.currentTimeMillis()
    )

    enum class DeviceType {
        LIGHT, THERMOSTAT, CURTAIN, LOCK, CAMERA, SENSOR, SWITCH, OUTLET, APPLIANCE, SPEAKER, TV, OTHER
    }

    enum class ProtocolType {
        MATTER, HOMEKIT, TUYA, MIJIA, ZIGBEE, WIFI, BLUETOOTH, OTHER
    }

    data class DeviceState(
        val isOn: Boolean = false,
        val brightness: Int = 100,
        val temperature: Float = 25f,
        val targetTemperature: Float = 25f,
        val humidity: Float = 50f,
        val position: Int = 0,  // 窗帘位置 0-100
        val isLocked: Boolean = true,
        val batteryLevel: Int = 100,
        val powerConsumption: Float = 0f
    )

    enum class DeviceCapability {
        ON_OFF, BRIGHTNESS, COLOR_TEMPERATURE, COLOR, TEMPERATURE_CONTROL,
        POSITION_CONTROL, LOCK_CONTROL, POWER_MONITORING, ENERGY_MONITORING
    }

    private val _devices = MutableStateFlow<List<SmartDevice>>(emptyList())
    val devices: StateFlow<List<SmartDevice>> = _devices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    /**
     * 添加设备
     */
    fun addDevice(device: SmartDevice): SmartDevice {
        val current = _devices.value.toMutableList()
        if (current.none { it.id == device.id }) {
            current.add(device)
            _devices.value = current
        }
        return device
    }

    /**
     * 移除设备
     */
    fun removeDevice(deviceId: String) {
        _devices.value = _devices.value.filterNot { it.id == deviceId }
    }

    /**
     * 获取设备
     */
    fun getDevice(deviceId: String): SmartDevice? {
        return _devices.value.firstOrNull { it.id == deviceId }
    }

    /**
     * 按类型获取设备
     */
    fun getDevicesByType(type: DeviceType): List<SmartDevice> {
        return _devices.value.filter { it.type == type }
    }

    /**
     * 按房间获取设备
     */
    fun getDevicesByRoom(room: String): List<SmartDevice> {
        return _devices.value.filter { it.room == room }
    }

    /**
     * 获取所有房间
     */
    fun getRooms(): List<String> {
        return _devices.value.map { it.room }.distinct().filter { it.isNotEmpty() }
    }

    // ==================== 设备发现与配对（6.2） ====================

    /**
     * 开始设备发现
     */
    fun startDiscovery(protocols: List<ProtocolType> = ProtocolType.values().toList()) {
        _isDiscovering.value = true
        scope.launch {
            val found = mutableListOf<SmartDevice>()
            val ssdp = transport.ssdpDiscover()
            found += ssdp.map { discovered ->
                SmartDevice(
                    id = discovered.id,
                    name = discovered.name.ifBlank { discovered.location },
                    type = inferType(discovered.rawType, discovered.server),
                    protocol = runCatching { ProtocolType.valueOf(discovered.protocol) }
                        .getOrDefault(ProtocolType.OTHER),
                    manufacturer = discovered.server,
                    model = discovered.location,
                    isOnline = true,
                    isConnected = false
                )
            }
            val gatewayDevices = transport.fetchDevices()
            found += gatewayDevices.map { discovered ->
                SmartDevice(
                    id = discovered.id,
                    name = discovered.name,
                    type = inferType(discovered.rawType, discovered.server),
                    protocol = runCatching { ProtocolType.valueOf(discovered.protocol) }
                        .getOrDefault(ProtocolType.WIFI),
                    model = discovered.server,
                    isOnline = true,
                    isConnected = true
                )
            }
            val filtered = found.filter { device -> protocols.contains(device.protocol) }
            val current = _devices.value.toMutableList()
            filtered.forEach { device ->
                val index = current.indexOfFirst { it.id == device.id }
                if (index >= 0) current[index] = device else current.add(device)
            }
            _devices.value = current
            _isDiscovering.value = false
            Log.i(TAG, "发现 ${filtered.size} 个设备（SSDP=${ssdp.size} 网关=${gatewayDevices.size}）")
        }
    }

    private fun inferType(rawType: String, server: String): DeviceType {
        val text = "$rawType $server".lowercase()
        return when {
            text.contains("light") || text.contains("lamp") || text.contains("bulb") -> DeviceType.LIGHT
            text.contains("thermostat") || text.contains("hvac") -> DeviceType.THERMOSTAT
            text.contains("curtain") || text.contains("blind") -> DeviceType.CURTAIN
            text.contains("lock") -> DeviceType.LOCK
            text.contains("camera") -> DeviceType.CAMERA
            text.contains("sensor") -> DeviceType.SENSOR
            text.contains("switch") -> DeviceType.SWITCH
            text.contains("outlet") || text.contains("plug") -> DeviceType.OUTLET
            text.contains("speaker") -> DeviceType.SPEAKER
            text.contains("tv") || text.contains("mediarenderer") -> DeviceType.TV
            else -> DeviceType.OTHER
        }
    }

    /** 向设备/网关下发指令；未配置网关时静默失败（本地状态仍乐观更新）。 */
    private fun dispatch(deviceId: String, command: String, params: Map<String, Any?> = emptyMap()) {
        scope.launch {
            val ok = transport.sendCommand(deviceId, command, params)
            if (!ok) Log.d(TAG, "指令未送达（未配置网关）: $deviceId $command")
        }
    }

    /**
     * 停止设备发现
     */
    fun stopDiscovery() {
        _isDiscovering.value = false
    }

    /**
     * 配对设备（配对码非空时校验为 4~8 位数字，符合常见 BLE/Matter 配对规范）
     */
    fun pairDevice(deviceId: String, pairingCode: String? = null): Boolean {
        if (pairingCode != null && !PAIRING_CODE_REGEX.matches(pairingCode)) {
            return false
        }
        val current = _devices.value.toMutableList()
        val index = current.indexOfFirst { it.id == deviceId }
        if (index >= 0) {
            current[index] = current[index].copy(isConnected = true, isOnline = true)
            _devices.value = current
            return true
        }
        return false
    }

    /**
     * 取消配对
     */
    fun unpairDevice(deviceId: String): Boolean {
        val current = _devices.value.toMutableList()
        val index = current.indexOfFirst { it.id == deviceId }
        if (index >= 0) {
            current[index] = current[index].copy(isConnected = false, isOnline = false)
            _devices.value = current
            return true
        }
        return false
    }

    // ==================== 设备控制（6.3） ====================

    /**
     * 开灯
     */
    fun turnOnLight(deviceId: String) {
        dispatch(deviceId, "on")
        updateDeviceState(deviceId) { it.copy(isOn = true) }
    }

    /**
     * 关灯
     */
    fun turnOffLight(deviceId: String) {
        dispatch(deviceId, "off")
        updateDeviceState(deviceId) { it.copy(isOn = false) }
    }

    /**
     * 设置亮度
     */
    fun setLightBrightness(deviceId: String, brightness: Int) {
        val value = brightness.coerceIn(0, 100)
        dispatch(deviceId, "brightness", mapOf("value" to value))
        updateDeviceState(deviceId) { it.copy(brightness = value) }
    }

    /**
     * 设置色温
     */
    fun setLightColorTemperature(deviceId: String, temperature: Int) {
        dispatch(deviceId, "color_temperature", mapOf("value" to temperature))
        updateDeviceState(deviceId) { it.copy(brightness = it.brightness) }
    }

    /**
     * 设置目标温度
     */
    fun setTargetTemperature(deviceId: String, temperature: Float) {
        val value = temperature.coerceIn(16f, 30f)
        dispatch(deviceId, "target_temperature", mapOf("value" to value))
        updateDeviceState(deviceId) { it.copy(targetTemperature = value) }
    }

    /**
     * 打开窗帘
     */
    fun openCurtain(deviceId: String) {
        dispatch(deviceId, "position", mapOf("value" to 100))
        updateDeviceState(deviceId) { it.copy(position = 100) }
    }

    /**
     * 关闭窗帘
     */
    fun closeCurtain(deviceId: String) {
        dispatch(deviceId, "position", mapOf("value" to 0))
        updateDeviceState(deviceId) { it.copy(position = 0) }
    }

    /**
     * 设置窗帘位置
     */
    fun setCurtainPosition(deviceId: String, position: Int) {
        val value = position.coerceIn(0, 100)
        dispatch(deviceId, "position", mapOf("value" to value))
        updateDeviceState(deviceId) { it.copy(position = value) }
    }

    /**
     * 开锁
     */
    fun unlock(deviceId: String) {
        dispatch(deviceId, "unlock")
        updateDeviceState(deviceId) { it.copy(isLocked = false) }
    }

    /**
     * 关锁
     */
    fun lock(deviceId: String) {
        dispatch(deviceId, "lock")
        updateDeviceState(deviceId) { it.copy(isLocked = true) }
    }

    /**
     * 控制摄像头
     * 支持 RTSP/ONVIF/厂商 SDK 协议
     */
    fun controlCamera(deviceId: String, action: CameraAction) {
        getDevice(deviceId) ?: return
        val command = when (action) {
            CameraAction.START_STREAMING -> "stream_start"
            CameraAction.STOP_STREAMING -> "stream_stop"
            CameraAction.START_RECORDING -> "record_start"
            CameraAction.STOP_RECORDING -> "record_stop"
            CameraAction.TAKE_SNAPSHOT -> "snapshot"
            CameraAction.PAN_LEFT -> "pan_left"
            CameraAction.PAN_RIGHT -> "pan_right"
            CameraAction.TILT_UP -> "tilt_up"
            CameraAction.TILT_DOWN -> "tilt_down"
        }
        dispatch(deviceId, command)
        when (action) {
            CameraAction.START_STREAMING, CameraAction.START_RECORDING ->
                updateDeviceState(deviceId) { it.copy(isOn = true) }
            CameraAction.STOP_STREAMING, CameraAction.STOP_RECORDING ->
                updateDeviceState(deviceId) { it.copy(isOn = false) }
            else -> Unit
        }
    }

    enum class CameraAction {
        START_STREAMING, STOP_STREAMING, START_RECORDING, STOP_RECORDING,
        TAKE_SNAPSHOT, PAN_LEFT, PAN_RIGHT, TILT_UP, TILT_DOWN
    }

    private fun updateDeviceState(deviceId: String, update: (DeviceState) -> DeviceState) {
        _devices.value = _devices.value.map { device ->
            if (device.id == deviceId) {
                device.copy(state = update(device.state))
            } else device
        }
    }

    // ==================== 设备分组（6.2） ====================

    /**
     * 设备组
     */
    data class DeviceGroup(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val deviceIds: List<String>,
        val groupType: GroupType
    )

    enum class GroupType {
        ROOM, FLOOR, FUNCTION, CUSTOM
    }

    private val _groups = MutableStateFlow<List<DeviceGroup>>(emptyList())
    val groups: StateFlow<List<DeviceGroup>> = _groups.asStateFlow()

    /**
     * 创建设备组
     */
    fun createGroup(name: String, deviceIds: List<String>, type: GroupType): DeviceGroup {
        val group = DeviceGroup(
            name = name,
            deviceIds = deviceIds,
            groupType = type
        )
        val current = _groups.value.toMutableList()
        current.add(group)
        _groups.value = current
        return group
    }

    /**
     * 控制组
     */
    fun controlGroup(groupId: String, action: DeviceControlAction) {
        val group = _groups.value.firstOrNull { it.id == groupId } ?: return
        group.deviceIds.forEach { deviceId ->
            when (action) {
                is DeviceControlAction.TurnOn -> turnOnDevice(deviceId)
                is DeviceControlAction.TurnOff -> turnOffDevice(deviceId)
                is DeviceControlAction.SetBrightness -> setLightBrightness(deviceId, action.value)
                is DeviceControlAction.SetTemperature -> setTargetTemperature(deviceId, action.value)
                is DeviceControlAction.OpenCurtain -> openCurtain(deviceId)
                is DeviceControlAction.CloseCurtain -> closeCurtain(deviceId)
                is DeviceControlAction.Lock -> lock(deviceId)
                is DeviceControlAction.Unlock -> unlock(deviceId)
            }
        }
    }

    sealed class DeviceControlAction {
        object TurnOn : DeviceControlAction()
        object TurnOff : DeviceControlAction()
        data class SetBrightness(val value: Int) : DeviceControlAction()
        data class SetTemperature(val value: Float) : DeviceControlAction()
        object OpenCurtain : DeviceControlAction()
        object CloseCurtain : DeviceControlAction()
        object Lock : DeviceControlAction()
        object Unlock : DeviceControlAction()
    }

    private fun turnOnDevice(deviceId: String) {
        dispatch(deviceId, "on")
        updateDeviceState(deviceId) { it.copy(isOn = true) }
    }

    private fun turnOffDevice(deviceId: String) {
        dispatch(deviceId, "off")
        updateDeviceState(deviceId) { it.copy(isOn = false) }
    }

    // ==================== 多设备协同（6.4） ====================

    /**
     * 场景
     */
    data class SmartScene(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val description: String = "",
        val actions: List<SceneAction>,
        val icon: String = "",
        val isEnabled: Boolean = true
    )

    sealed class SceneAction {
        data class DeviceAction(val deviceId: String, val action: DeviceControlAction) : SceneAction()
        data class DelayAction(val delayMs: Long) : SceneAction()
        data class NotifyAction(val message: String) : SceneAction()
    }

    private val _scenes = MutableStateFlow<List<SmartScene>>(emptyList())
    val scenes: StateFlow<List<SmartScene>> = _scenes.asStateFlow()

    /**
     * 创建场景
     */
    fun createScene(name: String, description: String, actions: List<SceneAction>): SmartScene {
        val scene = SmartScene(
            name = name,
            description = description,
            actions = actions
        )
        val current = _scenes.value.toMutableList()
        current.add(scene)
        _scenes.value = current
        return scene
    }

    /**
     * 激活场景
     */
    fun activateScene(sceneId: String) {
        val scene = _scenes.value.firstOrNull { it.id == sceneId } ?: return
        if (!scene.isEnabled) return

        scene.actions.forEach { action ->
            when (action) {
                is SceneAction.DeviceAction -> {
                    when (action.action) {
                        is DeviceControlAction.TurnOn -> turnOnDevice(action.deviceId)
                        is DeviceControlAction.TurnOff -> turnOffDevice(action.deviceId)
                        is DeviceControlAction.SetBrightness -> setLightBrightness(action.deviceId, action.action.value)
                        is DeviceControlAction.SetTemperature -> setTargetTemperature(action.deviceId, action.action.value)
                        is DeviceControlAction.OpenCurtain -> openCurtain(action.deviceId)
                        is DeviceControlAction.CloseCurtain -> closeCurtain(action.deviceId)
                        is DeviceControlAction.Lock -> lock(action.deviceId)
                        is DeviceControlAction.Unlock -> unlock(action.deviceId)
                    }
                }
                is SceneAction.DelayAction -> {
                    // 延迟执行
                }
                is SceneAction.NotifyAction -> {
                    // 发送通知
                }
            }
        }
    }

    /**
     * 删除场景
     */
    fun deleteScene(sceneId: String) {
        _scenes.value = _scenes.value.filterNot { it.id == sceneId }
    }

    // ==================== 自动化规则 ====================

    /**
     * 自动化规则
     */
    data class AutomationRule(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val triggers: List<RuleTrigger>,
        val actions: List<SceneAction>,
        val isEnabled: Boolean = true
    )

    sealed class RuleTrigger {
        data class TimeTrigger(val hour: Int, val minute: Int) : RuleTrigger()
        data class DeviceStateTrigger(val deviceId: String, val condition: String) : RuleTrigger()
        data class SensorTrigger(val sensorId: String, val threshold: Float) : RuleTrigger()
        data class LocationTrigger(val latitude: Double, val longitude: Double, val radius: Float) : RuleTrigger()
    }

    private val _automationRules = MutableStateFlow<List<AutomationRule>>(emptyList())
    val automationRules: StateFlow<List<AutomationRule>> = _automationRules.asStateFlow()

    /**
     * 创建自动化规则
     */
    fun createAutomationRule(name: String, triggers: List<RuleTrigger>, actions: List<SceneAction>): AutomationRule {
        val rule = AutomationRule(
            name = name,
            triggers = triggers,
            actions = actions
        )
        val current = _automationRules.value.toMutableList()
        current.add(rule)
        _automationRules.value = current
        return rule
    }

    /**
     * 检查触发器（#85 场景自动化触发）。
     *
     * 覆盖时间 / 设备状态 / 传感器阈值 / 位置四类触发条件，
     * 命中即执行规则动作，并对动作本身做真实分发。
     *
     * @param currentLocation 当前经纬度（可选，用于位置触发判定）
     * @param sensorValues 传感器实时读数表（传感器 ID → 数值）
     */
    fun checkTriggers(
        currentLocation: Pair<Double, Double>? = null,
        sensorValues: Map<String, Float> = emptyMap()
    ) {
        val now = java.util.Calendar.getInstance()
        val currentMinuteOfDay = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        _automationRules.value.filter { it.isEnabled }.forEach { rule ->
            val hit = rule.triggers.any { trigger ->
                when (trigger) {
                    is RuleTrigger.TimeTrigger -> {
                        // 允许 1 分钟宽限窗口，避免轮询错过整分时刻
                        val target = trigger.hour * 60 + trigger.minute
                        kotlin.math.abs(currentMinuteOfDay - target) <= 1
                    }
                    is RuleTrigger.DeviceStateTrigger -> {
                        val device = getDevice(trigger.deviceId) ?: return@any false
                        evaluateDeviceCondition(device, trigger.condition)
                    }
                    is RuleTrigger.SensorTrigger -> {
                        val value = sensorValues[trigger.sensorId]
                            ?: getDevice(trigger.sensorId)?.state?.let { s ->
                                // 无实时读数时退化用设备内记录的温度/湿度
                                if (s.temperature != 25f) s.temperature else s.humidity
                            }
                        value != null && value >= trigger.threshold
                    }
                    is RuleTrigger.LocationTrigger -> {
                        if (currentLocation == null) return@any false
                        distanceMeters(
                            currentLocation.first, currentLocation.second,
                            trigger.latitude, trigger.longitude
                        ) <= trigger.radius
                    }
                }
            }
            if (hit) executeRuleActions(rule.actions)
        }
    }

    /** 解析设备状态条件表达式，如 "isOn==true"、"temperature>28"、"isLocked==false"。 */
    private fun evaluateDeviceCondition(device: SmartDevice, condition: String): Boolean {
        val match = CONDITION_REGEX.matchEntire(condition.trim()) ?: return false
        val (field, op, rawValue) = match.destructured
        val state = device.state
        val actual: Any = when (field) {
            "isOn" -> state.isOn
            "isLocked" -> state.isLocked
            "brightness" -> state.brightness
            "temperature" -> state.temperature
            "humidity" -> state.humidity
            "position" -> state.position
            "batteryLevel" -> state.batteryLevel
            "isOnline" -> device.isOnline
            else -> return false
        }
        return compare(actual, op, rawValue)
    }

    private fun compare(actual: Any, op: String, raw: String): Boolean {
        if (actual is Boolean) {
            val expected = raw.equals("true", ignoreCase = true)
            return when (op) {
                "==" -> actual == expected
                "!=" -> actual != expected
                else -> false
            }
        }
        val a = (actual as? Number)?.toDouble() ?: return false
        val b = raw.toDoubleOrNull() ?: return false
        return when (op) {
            "==" -> a == b
            "!=" -> a != b
            ">" -> a > b
            ">=" -> a >= b
            "<" -> a < b
            "<=" -> a <= b
            else -> false
        }
    }

    /** 两点球面距离（米），用于位置触发判定。 */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
    }

    private fun executeRuleActions(actions: List<SceneAction>) {
        scope.launch {
            actions.forEach { action ->
                when (action) {
                    is SceneAction.DeviceAction -> executeDeviceAction(action.deviceId, action.action)
                    is SceneAction.DelayAction -> kotlinx.coroutines.delay(action.delayMs)
                    is SceneAction.NotifyAction -> postSceneNotification(action.message)
                }
            }
        }
    }

    private fun executeDeviceAction(deviceId: String, action: DeviceControlAction) {
        when (action) {
            is DeviceControlAction.TurnOn -> turnOnDevice(deviceId)
            is DeviceControlAction.TurnOff -> turnOffDevice(deviceId)
            is DeviceControlAction.SetBrightness -> setLightBrightness(deviceId, action.value)
            is DeviceControlAction.SetTemperature -> setTargetTemperature(deviceId, action.value)
            is DeviceControlAction.OpenCurtain -> openCurtain(deviceId)
            is DeviceControlAction.CloseCurtain -> closeCurtain(deviceId)
            is DeviceControlAction.Lock -> lock(deviceId)
            is DeviceControlAction.Unlock -> unlock(deviceId)
        }
    }

    private fun postSceneNotification(message: String) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        SCENE_CHANNEL_ID, "智能家居", android.app.NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            val notification = androidx.core.app.NotificationCompat.Builder(context, SCENE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("JARVIS 家居场景")
                .setContentText(message)
                .setAutoCancel(true)
                .build()
            manager.notify(SCENE_NOTIFICATION_ID, notification)
        }
    }

    // ==================== 预定义场景 ====================

    /**
     * 创建预定义场景
     */
    fun createPredefinedScenes() {
        // 回家场景
        createScene(
            name = "回家模式",
            description = "打开客厅灯，调节温度到舒适水平",
            actions = listOf(
                SceneAction.DeviceAction("", DeviceControlAction.TurnOn),
                SceneAction.DeviceAction("", DeviceControlAction.SetTemperature(25f)),
                SceneAction.NotifyAction("欢迎回家")
            )
        )

        // 离家场景
        createScene(
            name = "离家模式",
            description = "关闭所有灯，锁门，启动安防",
            actions = listOf(
                SceneAction.DeviceAction("", DeviceControlAction.TurnOff),
                SceneAction.DeviceAction("", DeviceControlAction.Lock),
                SceneAction.NotifyAction("已启动安防系统")
            )
        )

        // 睡眠场景
        createScene(
            name = "睡眠模式",
            description = "关闭所有灯，调节温度，锁门",
            actions = listOf(
                SceneAction.DeviceAction("", DeviceControlAction.TurnOff),
                SceneAction.DeviceAction("", DeviceControlAction.SetTemperature(22f)),
                SceneAction.DeviceAction("", DeviceControlAction.Lock),
                SceneAction.NotifyAction("晚安")
            )
        )

        // 起床场景
        createScene(
            name = "起床模式",
            description = "打开窗帘，调节温度，播放音乐",
            actions = listOf(
                SceneAction.DeviceAction("", DeviceControlAction.OpenCurtain),
                SceneAction.DeviceAction("", DeviceControlAction.SetTemperature(24f)),
                SceneAction.NotifyAction("早上好")
            )
        )
    }

    // ==================== 设备状态监控 ====================

    /**
     * 获取设备统计
     */
    fun getDeviceStatistics(): DeviceStatistics {
        val devices = _devices.value
        return DeviceStatistics(
            totalDevices = devices.size,
            onlineDevices = devices.count { it.isOnline },
            offlineDevices = devices.count { !it.isOnline },
            devicesByType = devices.groupBy { it.type }.mapValues { it.value.size },
            devicesByRoom = devices.groupBy { it.room }.mapValues { it.value.size },
            totalPowerConsumption = devices.sumOf { it.state.powerConsumption.toDouble() }.toFloat()
        )
    }

    data class DeviceStatistics(
        val totalDevices: Int,
        val onlineDevices: Int,
        val offlineDevices: Int,
        val devicesByType: Map<DeviceType, Int>,
        val devicesByRoom: Map<String, Int>,
        val totalPowerConsumption: Float
    )

    /**
     * 获取低电量设备
     */
    fun getLowBatteryDevices(threshold: Int = 20): List<SmartDevice> {
        return _devices.value.filter { it.state.batteryLevel < threshold }
    }

    /**
     * 获取离线设备
     */
    fun getOfflineDevices(): List<SmartDevice> {
        return _devices.value.filter { !it.isOnline }
    }

    companion object {
        private const val TAG = "SmartHomeManager"

        /** 配对码格式：4~8 位数字 */
        private val PAIRING_CODE_REGEX = Regex("^\\d{4,8}$")

        /** 设备状态条件表达式：字段 运算符 值，如 temperature>28 */
        private val CONDITION_REGEX = Regex("^([A-Za-z]+)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$")

        private const val SCENE_CHANNEL_ID = "jarvis_smarthome_scene"
        private const val SCENE_NOTIFICATION_ID = 7301
    }
}

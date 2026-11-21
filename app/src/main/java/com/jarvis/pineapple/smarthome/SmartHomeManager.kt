package com.jarvis.pineapple.smarthome

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
class SmartHomeManager internal constructor(
    private val transport: SmartHomeTransport,
    private val sceneNotifier: (String) -> Boolean
) {
    constructor(context: Context) : this(
        transport = DeviceTransport(context),
        sceneNotifier = { message -> postSceneNotification(context, message) }
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discoveryJob: Job? = null

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
        val colorTemperature: Int = 4000,
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
    private val _discoveryStatus = MutableStateFlow("")
    val discoveryStatus: StateFlow<String> = _discoveryStatus.asStateFlow()

    fun startDiscovery(protocols: List<ProtocolType> = ProtocolType.values().toList()) {
        discoveryJob?.cancel()
        _isDiscovering.value = true
        _discoveryStatus.value = "正在发现设备"
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
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
                _discoveryStatus.value = DeviceTransport.emptyScanMessage(transport.isConfigured, filtered.size)
                Log.i(TAG, "发现 ${filtered.size} 个设备（SSDP=${ssdp.size} 网关=${gatewayDevices.size}）")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _discoveryStatus.value = "发现失败：${e.message ?: "未知错误"}"
                Log.w(TAG, "设备发现失败", e)
            } finally {
                if (discoveryJob === coroutineContext[Job]) {
                    _isDiscovering.value = false
                }
            }
        }
        discoveryJob = job
        job.start()
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

    /**
     * 向设备/网关下发指令。未配置网关或设备不存在时返回中文失败原因，不伪造成功。
     */
    suspend fun dispatch(deviceId: String, command: String, params: Map<String, Any?> = emptyMap()): String =
        dispatchResult(deviceId, command, params)

    private suspend fun dispatchResult(
        deviceId: String,
        command: String,
        params: Map<String, Any?> = emptyMap(),
        onDelivered: (() -> Unit)? = null
    ): String {
        val known = _devices.value.any { it.id == deviceId }
        if (!transport.isConfigured || !known) {
            return DeviceTransport.controlResult(transport.isConfigured, known, delivered = false)
        }
        val delivered = transport.sendCommand(deviceId, command, params)
        if (delivered) onDelivered?.invoke()
        return DeviceTransport.controlResult(configured = true, deviceKnown = true, delivered = delivered)
    }

    /** 停止当前发现任务，已取消任务的结果不会覆盖后续发现状态。 */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        _isDiscovering.value = false
        _discoveryStatus.value = "已停止发现"
    }

    /** 通过网关执行真实配对，配对码非空时校验为 4~8 位数字。 */
    suspend fun pairDevice(deviceId: String, pairingCode: String? = null): Boolean {
        if (pairingCode != null && !PAIRING_CODE_REGEX.matches(pairingCode)) return false
        val params = pairingCode?.let { mapOf<String, Any?>("pairingCode" to it) }.orEmpty()
        val result = dispatchResult(deviceId, "pair", params) {
            updateDevice(deviceId) { it.copy(isConnected = true, isOnline = true) }
        }
        return result == DELIVERED
    }

    /** 通过网关执行真实取消配对。 */
    suspend fun unpairDevice(deviceId: String): Boolean {
        val result = dispatchResult(deviceId, "unpair") {
            updateDevice(deviceId) { it.copy(isConnected = false, isOnline = false) }
        }
        return result == DELIVERED
    }

    // ==================== 设备控制（6.3） ====================

    /**
     * 开灯
     */
    suspend fun turnOnLight(deviceId: String): String = dispatchResult(deviceId, "on") {
        updateDeviceState(deviceId) { it.copy(isOn = true) }
    }

    /** 关灯。 */
    suspend fun turnOffLight(deviceId: String): String = dispatchResult(deviceId, "off") {
        updateDeviceState(deviceId) { it.copy(isOn = false) }
    }

    /** 设置亮度，仅在网关确认送达后更新本地状态。 */
    suspend fun setLightBrightness(deviceId: String, brightness: Int): String {
        val value = brightness.coerceIn(0, 100)
        return dispatchResult(deviceId, "brightness", mapOf("value" to value)) {
            updateDeviceState(deviceId) { it.copy(brightness = value) }
        }
    }

    /** 设置色温，仅在网关确认送达后更新本地状态。 */
    suspend fun setLightColorTemperature(deviceId: String, temperature: Int): String {
        val value = temperature.coerceIn(2700, 6500)
        return dispatchResult(deviceId, "color_temperature", mapOf("value" to value)) {
            updateDeviceState(deviceId) { it.copy(colorTemperature = value) }
        }
    }

    /** 设置目标温度。 */
    suspend fun setTargetTemperature(deviceId: String, temperature: Float): String {
        val value = temperature.coerceIn(16f, 30f)
        return dispatchResult(deviceId, "target_temperature", mapOf("value" to value)) {
            updateDeviceState(deviceId) { it.copy(targetTemperature = value) }
        }
    }

    /** 打开窗帘。 */
    suspend fun openCurtain(deviceId: String): String = setCurtainPosition(deviceId, 100)

    /** 关闭窗帘。 */
    suspend fun closeCurtain(deviceId: String): String = setCurtainPosition(deviceId, 0)

    /** 设置窗帘位置。 */
    suspend fun setCurtainPosition(deviceId: String, position: Int): String {
        val value = position.coerceIn(0, 100)
        return dispatchResult(deviceId, "position", mapOf("value" to value)) {
            updateDeviceState(deviceId) { it.copy(position = value) }
        }
    }

    /** 开锁。 */
    suspend fun unlock(deviceId: String): String = dispatchResult(deviceId, "unlock") {
        updateDeviceState(deviceId) { it.copy(isLocked = false) }
    }

    /** 关锁。 */
    suspend fun lock(deviceId: String): String = dispatchResult(deviceId, "lock") {
        updateDeviceState(deviceId) { it.copy(isLocked = true) }
    }

    /** 通过已配置网关控制摄像头。 */
    suspend fun controlCamera(deviceId: String, action: CameraAction): String {
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
        return dispatchResult(deviceId, command) {
            when (action) {
                CameraAction.START_STREAMING, CameraAction.START_RECORDING ->
                    updateDeviceState(deviceId) { it.copy(isOn = true) }
                CameraAction.STOP_STREAMING, CameraAction.STOP_RECORDING ->
                    updateDeviceState(deviceId) { it.copy(isOn = false) }
                else -> Unit
            }
        }
    }

    enum class CameraAction {
        START_STREAMING, STOP_STREAMING, START_RECORDING, STOP_RECORDING,
        TAKE_SNAPSHOT, PAN_LEFT, PAN_RIGHT, TILT_UP, TILT_DOWN
    }

    private fun updateDevice(deviceId: String, update: (SmartDevice) -> SmartDevice) {
        _devices.value = _devices.value.map { device ->
            if (device.id == deviceId) update(device) else device
        }
    }

    private fun updateDeviceState(deviceId: String, update: (DeviceState) -> DeviceState) {
        updateDevice(deviceId) { it.copy(state = update(it.state)) }
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
    suspend fun controlGroup(groupId: String, action: DeviceControlAction): String {
        val group = _groups.value.firstOrNull { it.id == groupId } ?: return "未找到设备组"
        val deviceIds = group.deviceIds.filter { it.isNotBlank() }.distinct()
        if (deviceIds.isEmpty()) return "设备组为空"
        val failures = deviceIds.mapNotNull { deviceId ->
            val result = executeDeviceAction(deviceId, action)
            if (result == DELIVERED) null else "$deviceId：$result"
        }
        return if (failures.isEmpty()) DELIVERED else "部分指令失败：${failures.joinToString("；")}"
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
    suspend fun activateScene(sceneId: String): String {
        val scene = _scenes.value.firstOrNull { it.id == sceneId } ?: return "未找到场景"
        if (!scene.isEnabled) return "场景已禁用"
        return executeActions(scene.actions)
    }

    /** 顺序执行场景动作，并汇总所有未完成的动作。 */
    private suspend fun executeActions(actions: List<SceneAction>): String {
        if (actions.isEmpty()) return "场景没有动作"
        val failures = mutableListOf<String>()
        actions.forEachIndexed { index, action ->
            val number = index + 1
            when (action) {
                is SceneAction.DeviceAction -> {
                    val result = executeDeviceAction(action.deviceId, action.action)
                    if (result != DELIVERED) failures += "动作 $number：$result"
                }
                is SceneAction.DelayAction -> {
                    if (action.delayMs < 0) failures += "动作 $number：延迟时长无效"
                    else delay(action.delayMs)
                }
                is SceneAction.NotifyAction -> {
                    val posted = action.message.isNotBlank() &&
                        runCatching { sceneNotifier(action.message) }.getOrDefault(false)
                    if (!posted) failures += "动作 $number：通知发送失败"
                }
            }
        }
        return if (failures.isEmpty()) "场景已执行" else "场景执行失败：${failures.joinToString("；")}"
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
            val result = executeActions(actions)
            if (result != SCENE_EXECUTED) Log.w(TAG, result)
        }
    }

    private suspend fun executeDeviceAction(deviceId: String, action: DeviceControlAction): String =
        when (action) {
            is DeviceControlAction.TurnOn -> turnOnLight(deviceId)
            is DeviceControlAction.TurnOff -> turnOffLight(deviceId)
            is DeviceControlAction.SetBrightness -> setLightBrightness(deviceId, action.value)
            is DeviceControlAction.SetTemperature -> setTargetTemperature(deviceId, action.value)
            is DeviceControlAction.OpenCurtain -> openCurtain(deviceId)
            is DeviceControlAction.CloseCurtain -> closeCurtain(deviceId)
            is DeviceControlAction.Lock -> lock(deviceId)
            is DeviceControlAction.Unlock -> unlock(deviceId)
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

        private const val DELIVERED = "已下发"
        private const val SCENE_EXECUTED = "场景已执行"
        private const val SCENE_CHANNEL_ID = "jarvis_smarthome_scene"
        private const val SCENE_NOTIFICATION_ID = 7301

        private fun postSceneNotification(context: Context, message: String): Boolean = runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager ?: return@runCatching false
            if (!manager.areNotificationsEnabled()) return@runCatching false
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
            true
        }.getOrElse {
            Log.w(TAG, "发送场景通知失败", it)
            false
        }
    }
}

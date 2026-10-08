package com.jarvis.pineapple.smarthome

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 智能家居设备抽象
 */
interface SmartDevice {
    val id: String
    val name: String
    val type: DeviceType
    var isOnline: Boolean
    suspend fun turnOn()
    suspend fun turnOff()
    suspend fun getState(): Map<String, Any>
}

enum class DeviceType { LIGHT, THERMOSTAT, CURTAIN, LOCK, CAMERA, APPLIANCE, SENSOR }

/**
 * 智能家居适配层：Matter / HomeKit(桥接) / Tuya / 米家
 */
class SmartHomeAdapter {

    private val _devices = MutableStateFlow<List<SmartDevice>>(emptyList())
    val devices: StateFlow<List<SmartDevice>> = _devices.asStateFlow()

    /** 设备发现与配对 */
    fun discover(): List<SmartDevice> {
        // 预留：扫描 Matter / Tuya / 米家设备
        return _devices.value
    }

    fun addDevice(device: SmartDevice) {
        _devices.value = _devices.value + device
    }

    fun removeDevice(deviceId: String) {
        _devices.value = _devices.value.filterNot { it.id == deviceId }
    }

    /** 设备分组 */
    private val groups = mutableMapOf<String, MutableList<String>>()

    fun createGroup(name: String, deviceIds: List<String>) {
        groups[name] = deviceIds.toMutableList()
    }

    suspend fun groupControl(groupName: String, action: (SmartDevice) -> Unit) {
        groups[groupName]?.forEach { id ->
            _devices.value.firstOrNull { it.id == id }?.let { action(it) }
        }
    }

    /** 场景自动化触发 */
    private val scenes = mutableMapOf<String, () -> Unit>()
    fun registerScene(name: String, action: () -> Unit) { scenes[name] = action }
    fun triggerScene(name: String) { scenes[name]?.invoke() }
}

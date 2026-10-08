package com.jarvis.pineapple.system

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 设备状态监控：CPU/GPU/内存/温度/电池
 */
class DeviceMonitor(private val context: Context) {

    data class DeviceStatus(
        val cpuUsage: Float = 0f,
        val memoryUsageMb: Long = 0,
        val totalMemoryMb: Long = 0,
        val temperature: Float = 0f,
        val batteryLevel: Int = 0,
        val batteryTemperature: Float = 0f
    )

    private val _status = MutableStateFlow(DeviceStatus())
    val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    fun refresh() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

        _status.value = DeviceStatus(
            memoryUsageMb = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024),
            totalMemoryMb = memInfo.totalMem / (1024 * 1024),
            temperature = readCpuTemp(),
            batteryLevel = level,
            batteryTemperature = temp
        )
    }

    private fun readCpuTemp(): Float {
        val paths = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp"
        )
        for (p in paths) {
            runCatching {
                val value = File(p).readText().trim().toFloat()
                return if (value > 1000) value / 1000f else value
            }
        }
        return 0f
    }
}

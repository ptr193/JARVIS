package com.jarvis.pineapple.system

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 设备状态监控：CPU/内存/温度/电池。
 * CPU 使用率来自两次 `/proc/stat` 差分，缺文件时给出中文原因。
 */
class DeviceMonitor(private val context: Context) {

    data class DeviceStatus(
        val cpuUsage: Float = 0f,
        val cpuUnavailableReason: String? = null,
        val memoryUsageMb: Long = 0,
        val totalMemoryMb: Long = 0,
        val temperature: Float = 0f,
        val batteryLevel: Int = 0,
        val batteryTemperature: Float = 0f
    )

    private val _status = MutableStateFlow(DeviceStatus())
    val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    private var previousCpu: CpuSnapshot? = null

    fun refresh() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

        val cpuSample = sampleCpu()
        _status.value = DeviceStatus(
            cpuUsage = cpuSample.usagePercent,
            cpuUnavailableReason = cpuSample.unavailableReason,
            memoryUsageMb = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024),
            totalMemoryMb = memInfo.totalMem / (1024 * 1024),
            temperature = readCpuTemp(),
            batteryLevel = level,
            batteryTemperature = temp
        )
    }

    private fun sampleCpu(): CpuSample {
        val raw = readProcStat() ?: return CpuSample(0f, CPU_UNAVAILABLE)
        val current = parseCpuSnapshot(raw) ?: return CpuSample(0f, CPU_UNAVAILABLE)
        val previous = previousCpu
        previousCpu = current
        if (previous == null) return CpuSample(0f, null)
        val usage = usagePercent(previous, current)
        return if (usage == null) CpuSample(0f, CPU_UNAVAILABLE) else CpuSample(usage, null)
    }

    private fun readProcStat(): String? = runCatching {
        val file = File(PROC_STAT)
        if (!file.exists() || !file.canRead()) return null
        file.useLines { it.firstOrNull() }
    }.getOrNull()

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

    data class CpuSnapshot(val idle: Long, val total: Long)
    data class CpuSample(val usagePercent: Float, val unavailableReason: String?)

    companion object {
        const val PROC_STAT = "/proc/stat"
        const val CPU_UNAVAILABLE = "无法读取 CPU 使用率"

        fun parseCpuSnapshot(line: String): CpuSnapshot? {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.isEmpty() || !parts[0].startsWith("cpu")) return null
            val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 4) return null
            val idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L }
            val total = values.sum()
            if (total <= 0L) return null
            return CpuSnapshot(idle = idle, total = total)
        }

        fun usagePercent(previous: CpuSnapshot, current: CpuSnapshot): Float? {
            val totalDelta = current.total - previous.total
            val idleDelta = current.idle - previous.idle
            if (totalDelta <= 0L) return null
            val busy = (totalDelta - idleDelta).coerceAtLeast(0L)
            return (busy.toFloat() / totalDelta.toFloat() * 100f).coerceIn(0f, 100f)
        }
    }
}

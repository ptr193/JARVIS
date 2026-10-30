package com.jarvis.pineapple.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile

/**
 * 硬件诊断管理器（功能 #93-#95）。
 *
 * 实现真实的硬件故障检测、故障诊断和根因分析。
 */
class HardwareDiagnostics(private val context: Context) {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val _diagnosticsState = MutableStateFlow(DiagnosticsState())
    val diagnosticsState: StateFlow<DiagnosticsState> = _diagnosticsState.asStateFlow()

    data class DiagnosticsState(
        val isDiagnosticsRunning: Boolean = false,
        val lastDiagnosticsTime: Long = 0,
        val issues: List<HardwareIssue> = emptyList()
    )

    data class HardwareIssue(
        val component: String,
        val severity: IssueSeverity,
        val description: String,
        val recommendation: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class IssueSeverity {
        INFO, WARNING, CRITICAL
    }

    data class HardwareStatus(
        val cpuUsage: Float,
        val memoryUsageMb: Long,
        val totalMemoryMb: Long,
        val availableStorageMb: Long,
        val totalStorageMb: Long,
        val batteryLevel: Int,
        val batteryTemperature: Float,
        val batteryHealth: Int,
        val cpuTemperature: Float,
        val uptimeSeconds: Long
    )

    // ==================== 硬件状态采集 ====================

    /**
     * 采集硬件状态
     */
    fun collectHardwareStatus(): HardwareStatus {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val storageInfo = collectStorageInfo()
        val batteryInfo = collectBatteryInfo()

        return HardwareStatus(
            cpuUsage = readCpuUsage(),
            memoryUsageMb = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024),
            totalMemoryMb = memInfo.totalMem / (1024 * 1024),
            availableStorageMb = storageInfo.first,
            totalStorageMb = storageInfo.second,
            batteryLevel = batteryInfo.first,
            batteryTemperature = batteryInfo.second,
            batteryHealth = batteryInfo.third,
            cpuTemperature = readCpuTemperature(),
            uptimeSeconds = readUptime()
        )
    }

    /**
     * 读取 CPU 使用率
     */
    private fun readCpuUsage(): Float {
        return try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val load = reader.readLine()
            reader.close()

            val toks = load.split(" +".toRegex())
            val idle1 = toks[4].toLong()
            val cpu1 = toks[1].toLong() + toks[2].toLong() + toks[3].toLong() + toks[5].toLong() + toks[6].toLong() + toks[7].toLong()

            Thread.sleep(100)

            val reader2 = RandomAccessFile("/proc/stat", "r")
            val load2 = reader2.readLine()
            reader2.close()

            val toks2 = load2.split(" +".toRegex())
            val idle2 = toks2[4].toLong()
            val cpu2 = toks2[1].toLong() + toks2[2].toLong() + toks2[3].toLong() + toks2[5].toLong() + toks2[6].toLong() + toks2[7].toLong()

            val cpuDiff = cpu2 - cpu1
            val idleDiff = idle2 - idle1
            val totalDiff = cpuDiff + idleDiff

            if (totalDiff > 0) (cpuDiff.toFloat() / totalDiff * 100) else 0f
        } catch (e: Exception) {
            0f
        }
    }

    /**
     * 读取 CPU 温度
     */
    private fun readCpuTemperature(): Float {
        val thermalPaths = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/devices/system/cpu/cpu0/cpufreq/temp"
        )

        for (path in thermalPaths) {
            try {
                val temp = File(path).readText().trim().toFloat()
                return if (temp > 1000) temp / 1000f else temp
            } catch (_: Exception) {
                continue
            }
        }
        return 0f
    }

    /**
     * 采集存储信息
     */
    private fun collectStorageInfo(): Pair<Long, Long> {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val available = stat.availableBytes / (1024 * 1024)
            val total = stat.totalBytes / (1024 * 1024)
            Pair(available, total)
        } catch (e: Exception) {
            Pair(0, 0)
        }
    }

    /**
     * 采集电池信息
     */
    private fun collectBatteryInfo(): Triple<Int, Float, Int> {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

            val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val batteryTemp = temp / 10f

            val health = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
                ?: BatteryManager.BATTERY_HEALTH_UNKNOWN

            Triple(batteryPct, batteryTemp, health)
        } catch (e: Exception) {
            Triple(0, 0f, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        }
    }

    /**
     * 读取系统运行时间
     */
    private fun readUptime(): Long {
        return try {
            android.os.SystemClock.elapsedRealtime() / 1000
        } catch (e: Exception) {
            0
        }
    }

    // ==================== 硬件故障检测（#93） ====================

    /**
     * 执行完整硬件诊断
     */
    fun performFullDiagnostics(): List<HardwareIssue> {
        _diagnosticsState.value = _diagnosticsState.value.copy(isDiagnosticsRunning = true)

        val issues = mutableListOf<HardwareIssue>()
        val status = collectHardwareStatus()

        // CPU 使用率检查
        if (status.cpuUsage > 90) {
            issues.add(HardwareIssue(
                component = "CPU",
                severity = IssueSeverity.WARNING,
                description = "CPU 使用率过高（${status.cpuUsage.toInt()}%）",
                recommendation = "关闭高负载应用或重启设备"
            ))
        }

        // 内存检查
        val memoryUsagePercent = if (status.totalMemoryMb > 0) {
            status.memoryUsageMb.toFloat() / status.totalMemoryMb * 100
        } else 0f
        if (memoryUsagePercent > 85) {
            issues.add(HardwareIssue(
                component = "内存",
                severity = IssueSeverity.WARNING,
                description = "内存使用率过高（${memoryUsagePercent.toInt()}%）",
                recommendation = "清理后台应用释放内存"
            ))
        }

        // 存储检查
        val storageUsagePercent = if (status.totalStorageMb > 0) {
            (status.totalStorageMb - status.availableStorageMb).toFloat() / status.totalStorageMb * 100
        } else 0f
        if (storageUsagePercent > 90) {
            issues.add(HardwareIssue(
                component = "存储",
                severity = IssueSeverity.CRITICAL,
                description = "存储空间不足（已用 ${storageUsagePercent.toInt()}%）",
                recommendation = "删除不必要的文件或移动数据到外部存储"
            ))
        }

        // 电池检查
        if (status.batteryTemperature > 45) {
            issues.add(HardwareIssue(
                component = "电池",
                severity = IssueSeverity.WARNING,
                description = "电池温度过高（${status.batteryTemperature}°C）",
                recommendation = "停止使用设备，让其冷却"
            ))
        }

        if (status.batteryHealth != BatteryManager.BATTERY_HEALTH_GOOD && status.batteryHealth != BatteryManager.BATTERY_HEALTH_UNKNOWN) {
            issues.add(HardwareIssue(
                component = "电池",
                severity = IssueSeverity.CRITICAL,
                description = "电池健康状况不佳",
                recommendation = "建议更换电池"
            ))
        }

        // CPU 温度检查
        if (status.cpuTemperature > 80) {
            issues.add(HardwareIssue(
                component = "CPU 散热",
                severity = IssueSeverity.CRITICAL,
                description = "CPU 温度过高（${status.cpuTemperature}°C）",
                recommendation = "立即关闭高负载应用，检查散热"
            ))
        }

        // 系统运行时间检查
        if (status.uptimeSeconds > 7 * 24 * 60 * 60) {
            issues.add(HardwareIssue(
                component = "系统",
                severity = IssueSeverity.INFO,
                description = "系统已连续运行 ${status.uptimeSeconds / (24 * 60 * 60)} 天",
                recommendation = "建议定期重启设备以释放资源"
            ))
        }

        _diagnosticsState.value = DiagnosticsState(
            isDiagnosticsRunning = false,
            lastDiagnosticsTime = System.currentTimeMillis(),
            issues = issues
        )

        return issues
    }

    /**
     * 快速检查
     */
    fun quickCheck(): List<HardwareIssue> {
        val issues = mutableListOf<HardwareIssue>()
        val status = collectHardwareStatus()

        if (status.batteryLevel < 15) {
            issues.add(HardwareIssue(
                component = "电池",
                severity = IssueSeverity.WARNING,
                description = "电量低（${status.batteryLevel}%）",
                recommendation = "请连接充电器"
            ))
        }

        if (status.availableStorageMb < 500) {
            issues.add(HardwareIssue(
                component = "存储",
                severity = IssueSeverity.CRITICAL,
                description = "存储空间严重不足（剩余 ${status.availableStorageMb}MB）",
                recommendation = "立即清理存储空间"
            ))
        }

        return issues
    }

    // ==================== 故障诊断（#94） ====================

    /**
     * 诊断特定组件
     */
    fun diagnoseComponent(component: String): ComponentDiagnosis {
        val status = collectHardwareStatus()

        return when (component.lowercase()) {
            "cpu" -> diagnoseCpu(status)
            "memory" -> diagnoseMemory(status)
            "storage" -> diagnoseStorage(status)
            "battery" -> diagnoseBattery(status)
            else -> ComponentDiagnosis(
                component = component,
                isHealthy = true,
                status = "未知组件",
                recommendations = emptyList()
            )
        }
    }

    private fun diagnoseCpu(status: HardwareStatus): ComponentDiagnosis {
        val recommendations = mutableListOf<String>()

        if (status.cpuUsage > 80) {
            recommendations.add("CPU 负载较高，建议关闭不必要的后台应用")
        }
        if (status.cpuTemperature > 70) {
            recommendations.add("CPU 温度偏高，建议检查散热并降低负载")
        }
        if (status.uptimeSeconds > 3 * 24 * 60 * 60) {
            recommendations.add("系统运行时间较长，建议重启以清理内存碎片")
        }

        return ComponentDiagnosis(
            component = "CPU",
            isHealthy = recommendations.isEmpty(),
            status = "使用率: ${status.cpuUsage.toInt()}%, 温度: ${status.cpuTemperature}°C",
            recommendations = recommendations
        )
    }

    private fun diagnoseMemory(status: HardwareStatus): ComponentDiagnosis {
        val usagePercent = if (status.totalMemoryMb > 0) {
            status.memoryUsageMb.toFloat() / status.totalMemoryMb * 100
        } else 0f

        val recommendations = mutableListOf<String>()

        if (usagePercent > 80) {
            recommendations.add("内存使用率较高（${usagePercent.toInt()}%），建议清理后台应用")
        }
        if (usagePercent > 90) {
            recommendations.add("内存严重不足，建议立即释放内存")
        }

        return ComponentDiagnosis(
            component = "内存",
            isHealthy = recommendations.isEmpty(),
            status = "已用: ${status.memoryUsageMb}MB / 总共: ${status.totalMemoryMb}MB (${usagePercent.toInt()}%)",
            recommendations = recommendations
        )
    }

    private fun diagnoseStorage(status: HardwareStatus): ComponentDiagnosis {
        val usagePercent = if (status.totalStorageMb > 0) {
            (status.totalStorageMb - status.availableStorageMb).toFloat() / status.totalStorageMb * 100
        } else 0f

        val recommendations = mutableListOf<String>()

        if (usagePercent > 85) {
            recommendations.add("存储空间不足（已用 ${usagePercent.toInt()}%), 建议清理大文件")
        }
        if (status.availableStorageMb < 500) {
            recommendations.add("可用空间严重不足（${status.availableStorageMb}MB），可能影响系统运行")
        }

        return ComponentDiagnosis(
            component = "存储",
            isHealthy = recommendations.isEmpty(),
            status = "可用: ${status.availableStorageMb}MB / 总共: ${status.totalStorageMb}MB",
            recommendations = recommendations
        )
    }

    private fun diagnoseBattery(status: HardwareStatus): ComponentDiagnosis {
        val recommendations = mutableListOf<String>()

        if (status.batteryLevel < 20) {
            recommendations.add("电量低（${status.batteryLevel}%），建议充电")
        }
        if (status.batteryTemperature > 40) {
            recommendations.add("电池温度偏高（${status.batteryTemperature}°C），建议暂停使用")
        }

        val healthStatus = when (status.batteryHealth) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
            BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
            else -> "未知"
        }

        if (status.batteryHealth != BatteryManager.BATTERY_HEALTH_GOOD && status.batteryHealth != BatteryManager.BATTERY_HEALTH_UNKNOWN) {
            recommendations.add("电池健康状况: $healthStatus，建议更换电池")
        }

        return ComponentDiagnosis(
            component = "电池",
            isHealthy = recommendations.isEmpty(),
            status = "电量: ${status.batteryLevel}%, 温度: ${status.batteryTemperature}°C, 健康: $healthStatus",
            recommendations = recommendations
        )
    }

    data class ComponentDiagnosis(
        val component: String,
        val isHealthy: Boolean,
        val status: String,
        val recommendations: List<String>
    )

    // ==================== 根因分析（#95） ====================

    /**
     * 分析问题根因
     */
    fun analyzeRootCause(symptoms: List<String>): RootCauseAnalysis {
        val issues = performFullDiagnostics()
        val criticalIssues = issues.filter { it.severity == IssueSeverity.CRITICAL }
        val warningIssues = issues.filter { it.severity == IssueSeverity.WARNING }

        val rootCause = when {
            criticalIssues.isNotEmpty() -> {
                val critical = criticalIssues.first()
                "主要问题: ${critical.component} - ${critical.description}"
            }
            warningIssues.isNotEmpty() -> {
                "潜在问题: ${warningIssues.joinToString("; ") { "${it.component}: ${it.description}" }}"
            }
            else -> "未检测到明显问题，设备运行正常"
        }

        val allRecommendations = (criticalIssues + warningIssues).map { it.recommendation }.distinct()

        return RootCauseAnalysis(
            symptoms = symptoms,
            rootCause = rootCause,
            allIssues = issues,
            recommendations = allRecommendations,
            analysisTime = System.currentTimeMillis()
        )
    }

    data class RootCauseAnalysis(
        val symptoms: List<String>,
        val rootCause: String,
        val allIssues: List<HardwareIssue>,
        val recommendations: List<String>,
        val analysisTime: Long
    )
}

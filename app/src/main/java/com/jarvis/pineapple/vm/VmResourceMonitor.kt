package com.jarvis.pineapple.vm

import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 虚拟机资源使用情况
 */
data class VmResourceUsage(
    val vmId: String,
    val cpuUsagePercent: Float = 0f,
    val memoryUsedMb: Long = 0,
    val memoryTotalMb: Long = 0,
    val diskUsedGb: Float = 0f,
    val diskTotalGb: Float = 0f,
    val networkInKbps: Float = 0f,
    val networkOutKbps: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
) {
    val memoryUsagePercent: Float
        get() = if (memoryTotalMb > 0) (memoryUsedMb.toFloat() / memoryTotalMb * 100) else 0f

    val diskUsagePercent: Float
        get() = if (diskTotalGb > 0) (diskUsedGb / diskTotalGb * 100) else 0f
}

/**
 * 资源告警
 */
data class ResourceAlert(
    val vmId: String,
    val resourceType: ResourceType,
    val threshold: Float,
    val currentValue: Float,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val severity: AlertSeverity = AlertSeverity.WARNING
)

enum class ResourceType { CPU, MEMORY, DISK, NETWORK }
enum class AlertSeverity { INFO, WARNING, CRITICAL }

/**
 * 虚拟机资源监控器（REQ-01-S5）。
 *
 * PRoot 虚拟机和宿主机共享内核，因此资源使用情况直接读取宿主机
 * /proc 与 rootfs 文件系统：CPU（/proc/stat 差分）、内存（/proc/meminfo）、
 * 网络（/proc/net/dev 差分）、磁盘（rootfs 目录大小 + StatFs）。
 */
class VmResourceMonitor(
    private val vmId: String,
    private val rootfsPath: String
) {
    private val _usage = MutableStateFlow(VmResourceUsage(vmId = vmId))
    val usage: StateFlow<VmResourceUsage> = _usage.asStateFlow()

    private val _alerts = MutableStateFlow<List<ResourceAlert>>(emptyList())
    val alerts: StateFlow<List<ResourceAlert>> = _alerts.asStateFlow()

    private val history = ArrayDeque<VmResourceUsage>()

    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    private var lastNetRx = 0L
    private var lastNetTx = 0L
    private var lastSampleTime = 0L

    var cpuAlertThreshold = 80f
    var memoryAlertThreshold = 85f
    var diskAlertThreshold = 90f
    var networkAlertThreshold = 1000f

    fun refresh() {
        val now = System.currentTimeMillis()
        val cpu = readCpuPercent()
        val (memTotalKb, memAvailKb) = readMemInfo()
        val (rxBytes, txBytes) = readNetBytes()

        val elapsedSec = if (lastSampleTime > 0) (now - lastSampleTime) / 1000.0 else 0.0
        val inKbps = if (elapsedSec > 0 && rxBytes >= lastNetRx)
            ((rxBytes - lastNetRx) * 8.0 / 1000.0 / elapsedSec).toFloat() else 0f
        val outKbps = if (elapsedSec > 0 && txBytes >= lastNetTx)
            ((txBytes - lastNetTx) * 8.0 / 1000.0 / elapsedSec).toFloat() else 0f

        lastNetRx = rxBytes
        lastNetTx = txBytes
        lastSampleTime = now

        val rootfs = File(rootfsPath)
        val diskUsedBytes = if (rootfs.exists()) dirSize(rootfs) else 0L
        val stat = try { StatFs(rootfsPath.ifEmpty { "/" }) } catch (e: Exception) { StatFs("/") }
        val diskTotalBytes = stat.blockCountLong * stat.blockSizeLong

        val newUsage = VmResourceUsage(
            vmId = vmId,
            cpuUsagePercent = cpu,
            memoryUsedMb = ((memTotalKb - memAvailKb) / 1024).coerceAtLeast(0),
            memoryTotalMb = memTotalKb / 1024,
            diskUsedGb = diskUsedBytes / (1024f * 1024 * 1024),
            diskTotalGb = diskTotalBytes / (1024f * 1024 * 1024),
            networkInKbps = inKbps,
            networkOutKbps = outKbps,
            timestamp = now
        )
        _usage.value = newUsage
        history.addLast(newUsage)
        if (history.size > 720) history.removeFirst()
        checkAlerts(newUsage)
    }

    private fun readCpuPercent(): Float {
        return try {
            val line = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") } ?: return 0f
            val parts = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (parts.size < 4) return 0f
            val idle = parts[3] + (parts.getOrElse(4) { 0L }) // idle + iowait
            val total = parts.sum()
            val deltaTotal = total - lastCpuTotal
            val deltaIdle = idle - lastCpuIdle
            lastCpuTotal = total
            lastCpuIdle = idle
            if (deltaTotal <= 0) 0f
            else (((deltaTotal - deltaIdle).toFloat() / deltaTotal) * 100f).coerceIn(0f, 100f)
        } catch (e: Exception) {
            0f
        }
    }

    private fun readMemInfo(): Pair<Long, Long> {
        return try {
            var total = 0L
            var avail = 0L
            File("/proc/meminfo").forEachLine { line ->
                when {
                    line.startsWith("MemTotal:") -> total = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
                    line.startsWith("MemAvailable:") -> avail = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
                }
            }
            total to avail
        } catch (e: Exception) {
            0L to 0L
        }
    }

    private fun readNetBytes(): Pair<Long, Long> {
        return try {
            var rx = 0L
            var tx = 0L
            File("/proc/net/dev").forEachLine { line ->
                if (line.contains(":") && !line.trim().startsWith("lo")) {
                    val data = line.substringAfter(":").trim().split(Regex("\\s+"))
                    if (data.size >= 9) {
                        rx += data[0].toLongOrNull() ?: 0L
                        tx += data[8].toLongOrNull() ?: 0L
                    }
                }
            }
            rx to tx
        } catch (e: Exception) {
            0L to 0L
        }
    }

    private fun dirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { size += if (it.isDirectory) dirSize(it) else it.length() }
        return size
    }

    private fun checkAlerts(usage: VmResourceUsage) {
        val newAlerts = mutableListOf<ResourceAlert>()

        if (usage.cpuUsagePercent > cpuAlertThreshold) {
            newAlerts.add(ResourceAlert(
                vmId = vmId,
                resourceType = ResourceType.CPU,
                threshold = cpuAlertThreshold,
                currentValue = usage.cpuUsagePercent,
                message = "CPU 使用率超过阈值: ${usage.cpuUsagePercent}%",
                severity = if (usage.cpuUsagePercent > 95) AlertSeverity.CRITICAL else AlertSeverity.WARNING
            ))
        }

        if (usage.memoryUsagePercent > memoryAlertThreshold) {
            newAlerts.add(ResourceAlert(
                vmId = vmId,
                resourceType = ResourceType.MEMORY,
                threshold = memoryAlertThreshold,
                currentValue = usage.memoryUsagePercent,
                message = "内存使用率超过阈值: ${usage.memoryUsagePercent}%",
                severity = if (usage.memoryUsagePercent > 95) AlertSeverity.CRITICAL else AlertSeverity.WARNING
            ))
        }

        if (usage.diskUsagePercent > diskAlertThreshold) {
            newAlerts.add(ResourceAlert(
                vmId = vmId,
                resourceType = ResourceType.DISK,
                threshold = diskAlertThreshold,
                currentValue = usage.diskUsagePercent,
                message = "磁盘使用率超过阈值: ${usage.diskUsagePercent}%",
                severity = if (usage.diskUsagePercent > 95) AlertSeverity.CRITICAL else AlertSeverity.WARNING
            ))
        }

        if (usage.networkInKbps > networkAlertThreshold) {
            newAlerts.add(ResourceAlert(
                vmId = vmId,
                resourceType = ResourceType.NETWORK,
                threshold = networkAlertThreshold,
                currentValue = usage.networkInKbps,
                message = "网络流入超过阈值: ${usage.networkInKbps} KB/s",
                severity = AlertSeverity.WARNING
            ))
        }

        _alerts.value = newAlerts
    }

    fun getHistory(minutes: Int = 60): List<VmResourceUsage> {
        val cutoff = System.currentTimeMillis() - minutes * 60 * 1000L
        return history.filter { it.timestamp >= cutoff }
    }

    fun clearAlerts() {
        _alerts.value = emptyList()
    }

    fun getAlertCount(): Int = _alerts.value.size

    fun getCriticalAlertCount(): Int = _alerts.value.count { it.severity == AlertSeverity.CRITICAL }
}

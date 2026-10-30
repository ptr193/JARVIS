package com.jarvis.pineapple.security

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 入侵检测器（功能 #46）。
 *
 * 通过分析系统日志、网络连接和异常行为来检测潜在入侵。
 */
class IntrusionDetector(private val context: Context) {

    private val _detectionState = MutableStateFlow(DetectionState())
    val detectionState: StateFlow<DetectionState> = _detectionState.asStateFlow()

    /**
     * 检测状态
     */
    data class DetectionState(
        val isMonitoring: Boolean = false,
        val threatLevel: ThreatLevel = ThreatLevel.NONE,
        val alerts: List<IntrusionAlert> = emptyList(),
        val lastCheckTime: Long = 0
    )

    enum class ThreatLevel {
        NONE, LOW, MEDIUM, HIGH, CRITICAL
    }

    /**
     * 入侵警报
     */
    data class IntrusionAlert(
        val id: String,
        val type: AlertType,
        val severity: ThreatLevel,
        val description: String,
        val source: String,
        val timestamp: Long = System.currentTimeMillis(),
        val isResolved: Boolean = false
    )

    enum class AlertType {
        SUSPICIOUS_PROCESS,
        NETWORK_ANOMALY,
        FILE_INTEGRITY,
        PRIVILEGE_ESCALATION,
        UNAUTHORIZED_ACCESS
    }

    /**
     * 开始监控
     */
    fun startMonitoring() {
        _detectionState.value = _detectionState.value.copy(isMonitoring = true)
    }

    /**
     * 停止监控
     */
    fun stopMonitoring() {
        _detectionState.value = _detectionState.value.copy(isMonitoring = false)
    }

    /**
     * 执行完整检测
     */
    fun performFullDetection(): List<IntrusionAlert> {
        val alerts = mutableListOf<IntrusionAlert>()

        // 检测可疑进程
        alerts.addAll(detectSuspiciousProcesses())

        // 检测网络异常
        alerts.addAll(detectNetworkAnomalies())

        // 检测文件完整性
        alerts.addAll(detectFileIntegrityIssues())

        // 检测权限提升
        alerts.addAll(detectPrivilegeEscalation())

        // 检测未授权访问
        alerts.addAll(detectUnauthorizedAccess())

        val overallThreatLevel = if (alerts.isEmpty()) {
            ThreatLevel.NONE
        } else {
            alerts.maxOf { it.severity }
        }

        _detectionState.value = DetectionState(
            isMonitoring = _detectionState.value.isMonitoring,
            threatLevel = overallThreatLevel,
            alerts = alerts,
            lastCheckTime = System.currentTimeMillis()
        )

        return alerts
    }

    /**
     * 检测可疑进程
     */
    private fun detectSuspiciousProcesses(): List<IntrusionAlert> {
        val alerts = mutableListOf<IntrusionAlert>()

        try {
            val process = Runtime.getRuntime().exec("ps -A")
            val output = process.inputStream.bufferedReader().readText()

            // 检测已知恶意进程名称
            val suspiciousPatterns = listOf(
                "malware", "virus", "trojan", "backdoor", "rootkit",
                "exploit", "hack", "crack", "keygen"
            )

            val lines = output.lines()
            for (line in lines) {
                val lowerLine = line.lowercase()
                for (pattern in suspiciousPatterns) {
                    if (lowerLine.contains(pattern)) {
                        alerts.add(IntrusionAlert(
                            id = "proc_${System.currentTimeMillis()}",
                            type = AlertType.SUSPICIOUS_PROCESS,
                            severity = ThreatLevel.HIGH,
                            description = "检测到可疑进程: $line",
                            source = "进程监控"
                        ))
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // 无法执行命令
        }

        return alerts
    }

    /**
     * 检测网络异常
     */
    private fun detectNetworkAnomalies(): List<IntrusionAlert> {
        val alerts = mutableListOf<IntrusionAlert>()

        try {
            val process = Runtime.getRuntime().exec("netstat -an")
            val output = process.inputStream.bufferedReader().readText()

            // 检测异常端口连接
            val suspiciousPorts = listOf("4444", "5555", "6666", "7777", "8888", "31337")

            val lines = output.lines()
            for (line in lines) {
                for (port in suspiciousPorts) {
                    if (line.contains(":$port")) {
                        alerts.add(IntrusionAlert(
                            id = "net_${System.currentTimeMillis()}",
                            type = AlertType.NETWORK_ANOMALY,
                            severity = ThreatLevel.MEDIUM,
                            description = "检测到异常端口连接: $port",
                            source = "网络监控"
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            // 无法执行命令
        }

        return alerts
    }

    /**
     * 检测文件完整性问题
     */
    private fun detectFileIntegrityIssues(): List<IntrusionAlert> {
        val alerts = mutableListOf<IntrusionAlert>()

        // 检查关键系统文件是否被修改
        val criticalFiles = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/app/Superuser.apk"
        )

        for (file in criticalFiles) {
            try {
                if (File(file).exists()) {
                    alerts.add(IntrusionAlert(
                        id = "file_${System.currentTimeMillis()}",
                        type = AlertType.FILE_INTEGRITY,
                        severity = ThreatLevel.HIGH,
                        description = "检测到可疑文件: $file",
                        source = "文件完整性检查"
                    ))
                }
            } catch (e: Exception) {
                // 无法访问文件
            }
        }

        return alerts
    }

    /**
     * 检测权限提升
     */
    private fun detectPrivilegeEscalation(): List<IntrusionAlert> {
        val alerts = mutableListOf<IntrusionAlert>()

        try {
            val process = Runtime.getRuntime().exec("ls -la /data/local/tmp")
            val output = process.inputStream.bufferedReader().readText()

            // 检测临时目录中的可执行文件
            val lines = output.lines()
            for (line in lines) {
                if (line.contains("rwx") && line.contains(".so") || line.contains(".dex")) {
                    alerts.add(IntrusionAlert(
                        id = "priv_${System.currentTimeMillis()}",
                        type = AlertType.PRIVILEGE_ESCALATION,
                        severity = ThreatLevel.MEDIUM,
                        description = "检测到可疑可执行文件: $line",
                        source = "权限监控"
                    ))
                }
            }
        } catch (e: Exception) {
            // 无法执行命令
        }

        return alerts
    }

    /**
     * 检测未授权访问
     */
    private fun detectUnauthorizedAccess(): List<IntrusionAlert> {
        val alerts = mutableListOf<IntrusionAlert>()

        // 检查最近的登录尝试
        try {
            val process = Runtime.getRuntime().exec("last -n 20")
            val output = process.inputStream.bufferedReader().readText()

            // 检测失败的登录尝试
            val lines = output.lines()
            var failedAttempts = 0
            for (line in lines) {
                if (line.contains("failed") || line.contains("invalid")) {
                    failedAttempts++
                }
            }

            if (failedAttempts > 5) {
                alerts.add(IntrusionAlert(
                    id = "auth_${System.currentTimeMillis()}",
                    type = AlertType.UNAUTHORIZED_ACCESS,
                    severity = ThreatLevel.HIGH,
                    description = "检测到 $failedAttempts 次失败登录尝试",
                    source = "认证监控"
                ))
            }
        } catch (e: Exception) {
            // 无法执行命令
        }

        return alerts
    }

    /**
     * 快速检查
     */
    fun quickCheck(): List<IntrusionAlert> {
        return detectSuspiciousProcesses() + detectNetworkAnomalies()
    }

    /**
     * 解决警报
     */
    fun resolveAlert(alertId: String) {
        val currentAlerts = _detectionState.value.alerts.toMutableList()
        val index = currentAlerts.indexOfFirst { it.id == alertId }
        if (index >= 0) {
            currentAlerts[index] = currentAlerts[index].copy(isResolved = true)
            _detectionState.value = _detectionState.value.copy(
                alerts = currentAlerts
            )
        }
    }

    /**
     * 获取活跃警报
     */
    fun getActiveAlerts(): List<IntrusionAlert> {
        return _detectionState.value.alerts.filterNot { it.isResolved }
    }

    /**
     * 清除所有警报
     */
    fun clearAllAlerts() {
        _detectionState.value = _detectionState.value.copy(
            alerts = emptyList(),
            threatLevel = ThreatLevel.NONE
        )
    }
}

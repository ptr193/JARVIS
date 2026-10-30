package com.jarvis.pineapple.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 日志条目
 */
data class VmLogEntry(
    val id: String,
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val source: String = "",
    val message: String = ""
)

enum class LogLevel { DEBUG, INFO, WARNING, ERROR, CRITICAL }

/**
 * 虚拟机日志管理器（REQ-01-S7）。
 */
class VmLogManager(
    private val vmId: String
) {
    private val _logs = MutableStateFlow<List<VmLogEntry>>(emptyList())
    val logs: StateFlow<List<VmLogEntry>> = _logs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val maxLogEntries = 1000

    fun addLog(level: LogLevel, message: String, source: String = "") {
        val entry = VmLogEntry(
            id = "log_${System.currentTimeMillis()}_${_logs.value.size}",
            level = level,
            source = source,
            message = message
        )
        val updated = _logs.value + entry
        _logs.value = if (updated.size > maxLogEntries) {
            updated.takeLast(maxLogEntries)
        } else {
            updated
        }
    }

    fun getLogs(): List<VmLogEntry> {
        return _logs.value
    }

    fun getLogsByLevel(level: LogLevel): List<VmLogEntry> {
        return _logs.value.filter { it.level == level }
    }

    fun getErrorLogs(): List<VmLogEntry> {
        return _logs.value.filter { it.level >= LogLevel.ERROR }
    }

    fun getRecentLogs(count: Int = 50): List<VmLogEntry> {
        return _logs.value.takeLast(count)
    }

    fun searchLogs(query: String): List<VmLogEntry> {
        return _logs.value.filter {
            it.message.contains(query, ignoreCase = true) ||
            it.source.contains(query, ignoreCase = true)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun exportLogs(): String {
        return _logs.value.joinToString("\n") { entry ->
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(entry.timestamp))
            "[$time] [${entry.level}] ${entry.source}: ${entry.message}"
        }
    }

    fun getLogStats(): LogStats {
        val all = _logs.value
        return LogStats(
            totalCount = all.size,
            debugCount = all.count { it.level == LogLevel.DEBUG },
            infoCount = all.count { it.level == LogLevel.INFO },
            warningCount = all.count { it.level == LogLevel.WARNING },
            errorCount = all.count { it.level == LogLevel.ERROR },
            criticalCount = all.count { it.level == LogLevel.CRITICAL }
        )
    }

    data class LogStats(
        val totalCount: Int,
        val debugCount: Int,
        val infoCount: Int,
        val warningCount: Int,
        val errorCount: Int,
        val criticalCount: Int
    )
}

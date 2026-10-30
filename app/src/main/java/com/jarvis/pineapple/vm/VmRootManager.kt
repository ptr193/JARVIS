package com.jarvis.pineapple.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * Root 权限管理器（REQ-06, REQ-07）。
 *
 * 功能：
 * - 虚拟机 Root 权限支持
 * - JARVIS 以高级用户身份使用虚拟机
 * - Root 凭据管理
 * - 审计日志
 */
class VmRootManager(
    private val vmId: String
) {
    // ==================== Root 权限状态 ====================

    /**
     * Root 权限状态
     */
    data class RootPermissionState(
        val isRootEnabled: Boolean = true,
        val isJarvisRootAllowed: Boolean = true,
        val currentSessionUser: String = "root",
        val rootPasswordHash: String = "",
        val lastPasswordChangeTime: Long = 0,
        val auditLogEnabled: Boolean = true
    )

    private val _rootState = MutableStateFlow(RootPermissionState())
    val rootState: StateFlow<RootPermissionState> = _rootState.asStateFlow()

    // ==================== 审计日志 ====================

    /**
     * 审计日志条目
     */
    data class AuditLogEntry(
        val id: String,
        val timestamp: Long = System.currentTimeMillis(),
        val action: String,
        val user: String,
        val command: String,
        val result: String,
        val isRootAction: Boolean = false
    )

    private val _auditLog = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    val auditLog: StateFlow<List<AuditLogEntry>> = _auditLog.asStateFlow()

    // ==================== Root 权限管理 ====================

    /**
     * 启用/禁用 Root 权限
     */
    fun setRootEnabled(enabled: Boolean) {
        _rootState.value = _rootState.value.copy(isRootEnabled = enabled)
        addAuditLog("ROOT_TOGGLE", "system", if (enabled) "启用 Root 权限" else "禁用 Root 权限", "SUCCESS")
    }

    /**
     * 允许/禁止 JARVIS 使用 Root 权限
     */
    fun setJarvisRootAllowed(allowed: Boolean) {
        _rootState.value = _rootState.value.copy(isJarvisRootAllowed = allowed)
        addAuditLog("JARVIS_ROOT_TOGGLE", "jarvis", if (allowed) "允许 JARVIS 使用 Root" else "禁止 JARVIS 使用 Root", "SUCCESS")
    }

    /**
     * 检查是否可以使用 Root 权限
     */
    fun canUseRoot(): Boolean {
        return _rootState.value.isRootEnabled && _rootState.value.isJarvisRootAllowed
    }

    /**
     * 切换到 Root 用户
     */
    fun switchToRoot(): Boolean {
        if (!canUseRoot()) {
            addAuditLog("SWITCH_ROOT", "jarvis", "切换到 root 用户", "FAILED: Root 权限未启用")
            return false
        }
        _rootState.value = _rootState.value.copy(currentSessionUser = "root")
        addAuditLog("SWITCH_ROOT", "jarvis", "切换到 root 用户", "SUCCESS")
        return true
    }

    /**
     * 切换到普通用户
     */
    fun switchToUser(username: String = "user") {
        _rootState.value = _rootState.value.copy(currentSessionUser = username)
        addAuditLog("SWITCH_USER", "jarvis", "切换到用户: $username", "SUCCESS")
    }

    /**
     * 获取当前用户
     */
    fun getCurrentUser(): String {
        return _rootState.value.currentSessionUser
    }

    /**
     * 检查当前是否为 Root 用户
     */
    fun isCurrentUserRoot(): Boolean {
        return _rootState.value.currentSessionUser == "root"
    }

    // ==================== Root 密码管理 ====================

    /**
     * 设置 Root 密码
     */
    fun setRootPassword(password: String): Boolean {
        return try {
            val passwordHash = hashPassword(password)
            _rootState.value = _rootState.value.copy(
                rootPasswordHash = passwordHash,
                lastPasswordChangeTime = System.currentTimeMillis()
            )
            addAuditLog("SET_ROOT_PASSWORD", "system", "设置 Root 密码", "SUCCESS")
            true
        } catch (e: Exception) {
            addAuditLog("SET_ROOT_PASSWORD", "system", "设置 Root 密码", "FAILED: ${e.message}")
            false
        }
    }

    /**
     * 验证 Root 密码
     */
    fun verifyRootPassword(password: String): Boolean {
        val passwordHash = hashPassword(password)
        return passwordHash == _rootState.value.rootPasswordHash
    }

    /**
     * 重置 Root 密码
     */
    fun resetRootPassword(newPassword: String): Boolean {
        return setRootPassword(newPassword)
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    // ==================== 审计日志 ====================

    /**
     * 添加审计日志
     */
    fun addAuditLog(action: String, user: String, command: String, result: String) {
        if (!_rootState.value.auditLogEnabled) return

        val entry = AuditLogEntry(
            id = "audit_${System.currentTimeMillis()}",
            action = action,
            user = user,
            command = command,
            result = result,
            isRootAction = isCurrentUserRoot()
        )

        val current = _auditLog.value.toMutableList()
        current.add(entry)

        // 限制日志大小
        if (current.size > 1000) {
            _auditLog.value = current.takeLast(1000)
        } else {
            _auditLog.value = current
        }
    }

    /**
     * 获取审计日志
     */
    fun getAuditLog(): List<AuditLogEntry> {
        return _auditLog.value.sortedByDescending { it.timestamp }
    }

    /**
     * 获取 Root 操作日志
     */
    fun getRootActionLogs(): List<AuditLogEntry> {
        return _auditLog.value.filter { it.isRootAction }.sortedByDescending { it.timestamp }
    }

    /**
     * 清空审计日志
     */
    fun clearAuditLog() {
        _auditLog.value = emptyList()
    }

    /**
     * 导出审计日志
     */
    fun exportAuditLog(): String {
        return _auditLog.value.joinToString("\n") { entry ->
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(entry.timestamp))
            "[$time] [${entry.user}] ${entry.action}: ${entry.command} -> ${entry.result}"
        }
    }

    // ==================== 危险操作确认 ====================

    /**
     * 检查操作是否需要二次确认
     */
    fun needsConfirmation(action: String): Boolean {
        val dangerousActions = listOf(
            "rm", "format", "mkfs", "dd", "shutdown", "reboot",
            "iptables", "userdel", "passwd", "chmod 777"
        )
        return dangerousActions.any { action.contains(it, ignoreCase = true) }
    }

    /**
     * 执行危险操作（需要确认）
     */
    fun executeDangerousAction(action: String, confirmed: Boolean): Boolean {
        if (!confirmed) {
            addAuditLog("DANGEROUS_ACTION", getCurrentUser(), action, "CANCELLED: 未确认")
            return false
        }

        if (!canUseRoot()) {
            addAuditLog("DANGEROUS_ACTION", getCurrentUser(), action, "FAILED: 无 Root 权限")
            return false
        }

        addAuditLog("DANGEROUS_ACTION", getCurrentUser(), action, "EXECUTED")
        return true
    }

    // ==================== 权限降级 ====================

    /**
     * 降级到普通用户模式
     */
    fun degradeToUserMode() {
        _rootState.value = _rootState.value.copy(
            isJarvisRootAllowed = false,
            currentSessionUser = "user"
        )
        addAuditLog("DEGRADE", "system", "降级到普通用户模式", "SUCCESS")
    }

    /**
     * 获取权限状态摘要
     */
    fun getPermissionSummary(): String {
        val state = _rootState.value
        return buildString {
            appendLine("=== Root 权限状态 ===")
            appendLine("Root 启用: ${if (state.isRootEnabled) "是" else "否"}")
            appendLine("JARVIS Root 允许: ${if (state.isJarvisRootAllowed) "是" else "否"}")
            appendLine("当前用户: ${state.currentSessionUser}")
            appendLine("审计日志: ${if (state.auditLogEnabled) "启用" else "禁用"}")
            appendLine("最后密码修改: ${formatTime(state.lastPasswordChangeTime)}")
        }
    }

    private fun formatTime(timestamp: Long): String {
        if (timestamp == 0L) return "从未"
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
    }
}

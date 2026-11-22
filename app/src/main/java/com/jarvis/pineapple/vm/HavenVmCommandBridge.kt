package com.jarvis.pineapple.vm

import com.jarvis.pineapple.chat.VmCommandBridge
import com.jarvis.pineapple.chat.VmDesktopActionResult
import com.jarvis.pineapple.chat.VmRunResult
import com.jarvis.pineapple.chat.VmSummary

/**
 * [VmCommandBridge] 的真实实现：把对话指令转发到 [HavenVmManager] 的 PRoot 后端。
 */
class HavenVmCommandBridge(
    private val manager: HavenVmManager,
    private val desktopManager: VmDesktopManager,
    private val auditLog: VmAuditLog
) : VmCommandBridge {

    override suspend fun listVms(): List<VmSummary> =
        manager.vms.value.map { vm ->
            VmSummary(
                id = vm.id,
                name = vm.name,
                running = vm.state == HavenVmManager.HavenVmState.RUNNING
            )
        }

    override suspend fun start(vmId: String): Boolean = manager.startVm(vmId)

    override suspend fun run(vmId: String, command: String): VmRunResult {
        val result = manager.executeCommandResult(vmId, command)
            ?: return VmRunResult("Error: 虚拟机不存在", -1)
        // 特权审计：身份由全局 root 开关决定，关闭后以普通用户身份执行。
        auditLog.record(
            vmName = manager.findVm(vmId)?.name ?: vmId,
            identity = if (manager.isRootEnabled()) "root" else "user",
            command = command,
            exitCode = result.exitCode
        )
        return VmRunResult(result.output, result.exitCode)
    }

    override suspend fun openDesktop(vmId: String): VmDesktopActionResult {
        val status = desktopManager.start(vmId)
        return if (status.running) {
            VmDesktopActionResult(
                true,
                "已启动虚拟机桌面，VNC 端口 ${status.port}。可在“虚拟机 → 桌面”标签中操作。"
            )
        } else {
            VmDesktopActionResult(false, "桌面启动失败：${status.error ?: "未知原因"}")
        }
    }

    override suspend fun openBrowser(vmId: String): VmDesktopActionResult {
        val status = desktopManager.start(vmId)
        if (!status.running) {
            return VmDesktopActionResult(false, "桌面启动失败：${status.error ?: "未知原因"}")
        }
        val launched = desktopManager.launchBrowser(vmId)
        return if (launched) {
            VmDesktopActionResult(
                true,
                "已在虚拟机桌面启动浏览器（VNC 端口 ${status.port}）。"
            )
        } else {
            VmDesktopActionResult(
                false,
                "启动浏览器失败：虚拟机内未安装浏览器或桌面未运行。可先执行 " +
                    "“虚拟机 <名称> 执行 apt-get install -y firefox-esr”（按发行版选择包管理器）。"
            )
        }
    }
}

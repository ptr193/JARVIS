package com.jarvis.pineapple.vm

import com.jarvis.pineapple.chat.VmCommandBridge
import com.jarvis.pineapple.chat.VmRunResult
import com.jarvis.pineapple.chat.VmSummary

/**
 * [VmCommandBridge] 的真实实现：把对话指令转发到 [HavenVmManager] 的 PRoot 后端。
 */
class HavenVmCommandBridge(
    private val manager: HavenVmManager
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
        return VmRunResult(result.output, result.exitCode)
    }
}

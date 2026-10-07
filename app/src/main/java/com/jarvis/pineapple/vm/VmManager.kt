package com.jarvis.pineapple.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 虚拟机状态
 */
enum class VmState { STOPPED, RUNNING, ERROR }

/**
 * 虚拟机实例
 */
data class VirtualMachine(
    val id: String,
    val name: String,
    val distro: String,
    val state: VmState = VmState.STOPPED
)

/**
 * 虚拟机管理器：创建/启动/停止/删除，多虚拟机独立运行。
 * 底层基于 proot 实现（预留）。
 */
class VmManager {

    private val _vms = MutableStateFlow<List<VirtualMachine>>(emptyList())
    val vms: StateFlow<List<VirtualMachine>> = _vms.asStateFlow()

    fun create(name: String, distro: String): VirtualMachine {
        val vm = VirtualMachine(id = "vm_${System.currentTimeMillis()}", name = name, distro = distro)
        _vms.value = _vms.value + vm
        return vm
    }

    fun start(vmId: String) {
        update(vmId) { it.copy(state = VmState.RUNNING) }
    }

    fun stop(vmId: String) {
        update(vmId) { it.copy(state = VmState.STOPPED) }
    }

    fun delete(vmId: String) {
        _vms.value = _vms.value.filterNot { it.id == vmId }
    }

    private fun update(vmId: String, transform: (VirtualMachine) -> VirtualMachine) {
        _vms.value = _vms.value.map { if (it.id == vmId) transform(it) else it }
    }

    /**
     * 虚拟机间通信通道（预留）
     */
    fun createChannel(vmA: String, vmB: String) {
        // 预留：通过 proot 挂载点或 socket 实现
    }
}

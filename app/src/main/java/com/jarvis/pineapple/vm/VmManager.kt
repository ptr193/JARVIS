package com.jarvis.pineapple.vm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

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
 * 虚拟机间通信通道。
 *
 * 通过一个真实的共享目录（可通过 PRoot `-b` 绑定进两个虚拟机）实现文件级通信。
 */
data class VmChannel(
    val id: String,
    val vmIdA: String,
    val vmIdB: String,
    val sharedPath: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 虚拟机管理器：注册/启动/停止/删除虚拟机，并管理虚拟机间的共享通信通道。
 */
class VmManager internal constructor(private val sharedDir: File) {

    constructor(context: Context) : this(File(context.filesDir, "vm_shared"))

    private val _vms = MutableStateFlow<List<VirtualMachine>>(emptyList())
    val vms: StateFlow<List<VirtualMachine>> = _vms.asStateFlow()

    private val _channels = MutableStateFlow<List<VmChannel>>(emptyList())
    val channels: StateFlow<List<VmChannel>> = _channels.asStateFlow()

    private var counter = 0

    init {
        if (!sharedDir.exists()) sharedDir.mkdirs()
    }

    fun create(name: String, distro: String): VirtualMachine {
        val vm = VirtualMachine(
            id = "vm_${System.currentTimeMillis()}_${counter++}",
            name = name,
            distro = distro
        )
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
        _channels.value
            .filter { it.vmIdA == vmId || it.vmIdB == vmId }
            .forEach { removeChannel(it.id) }
    }

    private fun update(vmId: String, transform: (VirtualMachine) -> VirtualMachine) {
        _vms.value = _vms.value.map { if (it.id == vmId) transform(it) else it }
    }

    /**
     * 创建虚拟机间通信通道（真实创建共享目录）。
     */
    fun createChannel(vmA: String, vmB: String): VmChannel {
        val id = "chan_${System.currentTimeMillis()}_${counter++}"
        val dir = File(sharedDir, id)
        if (!dir.exists()) dir.mkdirs()
        val channel = VmChannel(id = id, vmIdA = vmA, vmIdB = vmB, sharedPath = dir.absolutePath)
        _channels.value = _channels.value + channel
        return channel
    }

    fun getChannels(): List<VmChannel> = _channels.value

    fun getChannelsForVm(vmId: String): List<VmChannel> =
        _channels.value.filter { it.vmIdA == vmId || it.vmIdB == vmId }

    fun removeChannel(channelId: String): Boolean {
        val channel = _channels.value.firstOrNull { it.id == channelId } ?: return false
        File(channel.sharedPath).deleteRecursively()
        _channels.value = _channels.value.filterNot { it.id == channelId }
        return true
    }
}

package com.jarvis.pineapple.vm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 基于 PRoot 的 Linux 虚拟机管理器。
 *
 * 每个虚拟机对应一个已安装的 aarch64 rootfs，通过 PRoot 在用户态运行，
 * 支持：创建、启动、停止、删除、执行命令、软件包管理与桌面会话安装。
 *
 * 所有操作均基于真实的 [ProotManager] 执行，不产生模拟输出。
 */
class HavenVmManager(
    private val context: Context,
    private val prootManager: ProotManager,
    private val distroManager: VmDistroManager
) {

    private val _vms = MutableStateFlow<List<HavenVm>>(emptyList())
    val vms: StateFlow<List<HavenVm>> = _vms.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    /**
     * 虚拟机实例
     */
    data class HavenVm(
        val id: String,
        val name: String,
        val distroId: String,
        val distroName: String,
        val packageManager: String,
        val rootfsPath: String,
        val state: HavenVmState = HavenVmState.STOPPED,
        val installedPackages: List<String> = emptyList(),
        val createdAt: Long = System.currentTimeMillis(),
        val lastStartedAt: Long = 0
    )

    enum class HavenVmState {
        STOPPED, STARTING, RUNNING, STOPPING, ERROR
    }

    // ==================== 生命周期 ====================

    /**
     * 创建虚拟机（若对应发行版未安装则先下载）。
     */
    suspend fun createVm(name: String, distroId: String): HavenVm? {
        _isCreating.value = true
        try {
            val distro = distroManager.findDistro(distroId) ?: return null
            if (!distro.isDownloaded) {
                if (!distroManager.downloadDistro(distroId)) return null
            }
            val rootfs = distroManager.rootfsDir(distroId)
            if (!rootfs.exists() || rootfs.listFiles().isNullOrEmpty()) return null

            val vm = HavenVm(
                id = "vm_${System.currentTimeMillis()}",
                name = name.ifBlank { distro.name },
                distroId = distroId,
                distroName = distro.name,
                packageManager = distro.packageManager,
                rootfsPath = rootfs.absolutePath
            )
            _vms.value = _vms.value + vm
            return vm
        } finally {
            _isCreating.value = false
        }
    }

    /**
     * 启动虚拟机（探测 PRoot 环境可用性）。
     */
    suspend fun startVm(vmId: String): Boolean {
        val vm = findVm(vmId) ?: return false
        updateVm(vmId) { it.copy(state = HavenVmState.STARTING) }

        val config = configFor(vm)
        val probe = prootManager.executeCommand(config, "echo JARVIS_VM_OK")
        return if (probe.exitCode == 0 && probe.output.contains("JARVIS_VM_OK")) {
            updateVm(vmId) {
                it.copy(state = HavenVmState.RUNNING, lastStartedAt = System.currentTimeMillis())
            }
            refreshInstalledPackages(vmId)
            true
        } else {
            updateVm(vmId) { it.copy(state = HavenVmState.ERROR) }
            false
        }
    }

    /**
     * 停止虚拟机。
     */
    fun stopVm(vmId: String): Boolean {
        val vm = findVm(vmId) ?: return false
        updateVm(vmId) { it.copy(state = HavenVmState.STOPPING) }
        updateVm(vmId) { it.copy(state = HavenVmState.STOPPED) }
        return true
    }

    /**
     * 删除虚拟机（保留共享的 rootfs）。
     */
    suspend fun deleteVm(vmId: String): Boolean {
        stopVm(vmId)
        val existed = findVm(vmId) != null
        _vms.value = _vms.value.filterNot { it.id == vmId }
        return existed
    }

    // ==================== 命令 / 软件包 ====================

    /**
     * 在虚拟机上执行命令。
     */
    suspend fun executeCommand(vmId: String, command: String): String {
        val vm = findVm(vmId) ?: return "Error: 虚拟机不存在"
        val config = configFor(vm)
        return prootManager.executeCommand(config, command).output
    }

    /**
     * 安装软件包。
     */
    suspend fun installPackage(vmId: String, packageName: String): Boolean {
        val vm = findVm(vmId) ?: return false
        val ok = prootManager.installPackage(configFor(vm), vm.packageManager, packageName)
        if (ok) refreshInstalledPackages(vmId)
        return ok
    }

    /**
     * 卸载软件包。
     */
    suspend fun uninstallPackage(vmId: String, packageName: String): Boolean {
        val vm = findVm(vmId) ?: return false
        val ok = prootManager.uninstallPackage(configFor(vm), vm.packageManager, packageName)
        if (ok) refreshInstalledPackages(vmId)
        return ok
    }

    /**
     * 更新所有软件包。
     */
    suspend fun updatePackages(vmId: String): Boolean {
        val vm = findVm(vmId) ?: return false
        return prootManager.updatePackages(configFor(vm), vm.packageManager)
    }

    /**
     * 从 guest 刷新已安装软件包列表。
     */
    suspend fun refreshInstalledPackages(vmId: String) {
        val vm = findVm(vmId) ?: return
        val packages = prootManager.listInstalledPackages(configFor(vm), vm.packageManager)
        if (packages.isNotEmpty()) {
            updateVm(vmId) { it.copy(installedPackages = packages) }
        }
    }

    // ==================== 查询 ====================

    fun findVm(vmId: String): HavenVm? = _vms.value.firstOrNull { it.id == vmId }

    fun getVmStatus(vmId: String): HavenVm? = findVm(vmId)

    fun getRunningVms(): List<HavenVm> = _vms.value.filter { it.state == HavenVmState.RUNNING }

    fun getInstalledPackages(vmId: String): List<String> =
        findVm(vmId)?.installedPackages ?: emptyList()

    fun configFor(vm: HavenVm): ProotManager.ProotConfig =
        ProotManager.ProotConfig(rootfsPath = vm.rootfsPath, workingDir = "/root")

    /**
     * 安装桌面会话所需组件并返回可用性说明。
     * 依据发行版包管理器安装 Wayland / X 及 VNC 服务端。
     */
    suspend fun installDesktopEnvironment(vmId: String): Boolean {
        val vm = findVm(vmId) ?: return false
        val command = when (vm.packageManager) {
            "apk" -> "apk add --no-cache xvfb x11vnc xterm"
            "apt" -> "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y xvfb x11vnc xterm"
            "pacman" -> "pacman -Sy --noconfirm xorg-server-xvfb x11vnc xterm"
            "xbps" -> "xbps-install -y xvfb x11vnc xterm"
            else -> return false
        }
        val result = prootManager.executeCommand(configFor(vm), command)
        return result.exitCode == 0
    }

    private fun updateVm(vmId: String, transform: (HavenVm) -> HavenVm) {
        _vms.value = _vms.value.map { if (it.id == vmId) transform(it) else it }
    }

    /** context 保留用于未来的会话存储/日志扩展。 */
    @Suppress("unused")
    private fun storageDir(): File = File(context.filesDir, "vm")
}

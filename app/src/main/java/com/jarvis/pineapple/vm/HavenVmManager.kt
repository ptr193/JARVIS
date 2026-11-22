package com.jarvis.pineapple.vm

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
    private val prootManager: ProotManager,
    private val distroManager: VmDistroManager,
    private val asRoot: () -> Boolean = { true }
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
        val lastStartedAt: Long = 0,
        val ownsRootfs: Boolean = false
    )

    enum class HavenVmState {
        STOPPED, STARTING, RUNNING, STOPPING, ERROR
    }

    // ==================== 生命周期 ====================

    data class CreateVmResult(
        val vm: HavenVm? = null,
        val error: String? = null
    ) {
        val isSuccess: Boolean get() = vm != null
    }

    /**
     * 创建虚拟机（若对应发行版未安装则先下载）。
     * 失败时 [CreateVmResult.error] 为中文原因：发行版未知 / 未下载 / rootfs 为空 / PRoot 未安装。
     */
    suspend fun createVm(name: String, distroId: String): CreateVmResult {
        _isCreating.value = true
        try {
            if (!prootManager.isProotInstalled()) {
                return CreateVmResult(error = createFailureReason(prootInstalled = false))
            }
            val distro = distroManager.findDistro(distroId)
                ?: return CreateVmResult(error = createFailureReason(distroExists = false))
            if (!distro.isDownloaded) {
                if (!distroManager.downloadDistro(distroId)) {
                    val progress = distroManager.getDownloadProgress(distroId)
                    return CreateVmResult(
                        error = createFailureReason(
                            distroDownloaded = false,
                            downloadError = progress?.errorMessage
                        )
                    )
                }
            }
            val rootfs = distroManager.rootfsDir(distroId)
            if (!rootfs.exists() || rootfs.listFiles().isNullOrEmpty()) {
                return CreateVmResult(error = createFailureReason(rootfsReady = false))
            }

            val vm = HavenVm(
                id = "vm_${System.currentTimeMillis()}",
                name = name.ifBlank { distro.name },
                distroId = distroId,
                distroName = distro.name,
                packageManager = distro.packageManager,
                rootfsPath = rootfs.absolutePath
            )
            _vms.value = _vms.value + vm
            return CreateVmResult(vm = vm)
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
        return if (probe.exitCode == 0 && probe.output.contains("JARVIS_VM_OK") && prootManager.startSession(config)) {
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

    /** 停止真实 PRoot 会话，并仅在进程退出后更新状态。 */
    suspend fun stopVm(vmId: String): Boolean {
        val vm = findVm(vmId) ?: return false
        updateVm(vmId) { it.copy(state = HavenVmState.STOPPING) }
        val stopped = prootManager.stopSession(configFor(vm))
        updateVm(vmId) {
            it.copy(state = if (stopped) HavenVmState.STOPPED else HavenVmState.RUNNING)
        }
        return stopped
    }

    /** 删除虚拟机登记；共享发行版 rootfs 保留，导入虚拟机的独立 rootfs 一并删除。 */
    suspend fun deleteVm(vmId: String): Boolean {
        val vm = findVm(vmId) ?: return false
        if (!stopVm(vmId)) return false
        if (vm.ownsRootfs && !File(vm.rootfsPath).deleteRecursively()) return false
        _vms.value = _vms.value.filterNot { it.id == vmId }
        return true
    }

    /** 将已解压且位于应用 rootfs 目录内的系统登记为可启动虚拟机。 */
    suspend fun createImportedVm(name: String, rootfsPath: String): CreateVmResult {
        _isCreating.value = true
        return try {
            if (!prootManager.isProotInstalled()) {
                CreateVmResult(error = "PRoot 未安装")
            } else {
                val rootfs = File(rootfsPath).canonicalFile
                val managedRoot = prootManager.rootfsRoot().canonicalFile
                val packageManager = detectPackageManager(rootfs)
                when {
                    !rootfs.path.startsWith(managedRoot.path + File.separator) ->
                        CreateVmResult(error = "导入目录不在应用 rootfs 中")
                    !rootfs.isDirectory || rootfs.listFiles().isNullOrEmpty() ->
                        CreateVmResult(error = "rootfs 为空")
                    packageManager == null ->
                        CreateVmResult(error = "无法识别 rootfs 的包管理器")
                    else -> {
                        val id = "vm_imported_${System.currentTimeMillis()}"
                        val displayName = name.ifBlank { "导入的 Linux" }
                        val vm = HavenVm(
                            id = id,
                            name = displayName,
                            distroId = id,
                            distroName = displayName,
                            packageManager = packageManager,
                            rootfsPath = rootfs.absolutePath,
                            ownsRootfs = true
                        )
                        _vms.value = _vms.value + vm
                        CreateVmResult(vm = vm)
                    }
                }
            }
        } catch (e: Exception) {
            CreateVmResult(error = "导入虚拟机失败: ${e.message}")
        } finally {
            _isCreating.value = false
        }
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

    /** 在虚拟机上执行命令并返回完整结果（含退出码）；虚拟机不存在返回 null。 */
    suspend fun executeCommandResult(vmId: String, command: String): ProotManager.CommandResult? {
        val vm = findVm(vmId) ?: return null
        return prootManager.executeCommand(configFor(vm), command)
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
    suspend fun refreshInstalledPackages(vmId: String): Boolean {
        val vm = findVm(vmId) ?: return false
        val result = prootManager.listInstalledPackages(configFor(vm), vm.packageManager)
        if (result.exitCode != 0) return false
        val packages = result.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        updateVm(vmId) { it.copy(installedPackages = packages) }
        return true
    }

    // ==================== 查询 ====================

    fun findVm(vmId: String): HavenVm? = _vms.value.firstOrNull { it.id == vmId }

    fun getVmStatus(vmId: String): HavenVm? = findVm(vmId)

    fun getRunningVms(): List<HavenVm> = _vms.value.filter { it.state == HavenVmState.RUNNING }

    fun getInstalledPackages(vmId: String): List<String> =
        findVm(vmId)?.installedPackages ?: emptyList()

    fun configFor(vm: HavenVm): ProotManager.ProotConfig =
        ProotManager.ProotConfig(rootfsPath = vm.rootfsPath, workingDir = "/root", asRoot = asRoot())

    /**
     * 安装桌面会话所需组件并返回可用性说明。
     * 依据发行版包管理器安装 Wayland / X 及 VNC 服务端。
     */
    suspend fun installDesktopEnvironment(vmId: String): Boolean {
        val vm = findVm(vmId) ?: return false
        val command = when (vm.packageManager) {
            "apk" -> "apk add --no-cache xvfb x11vnc xfce4 xfce4-terminal dbus"
            "apt" -> "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y xvfb x11vnc xfce4 xfce4-terminal dbus-x11"
            "pacman" -> "pacman -Sy --noconfirm xorg-server-xvfb x11vnc xfce4 xfce4-terminal dbus"
            "xbps" -> "xbps-install -y xvfb x11vnc xfce4 xfce4-terminal dbus"
            else -> return false
        }
        val result = prootManager.executeCommand(configFor(vm), command)
        return result.exitCode == 0
    }

    private fun updateVm(vmId: String, transform: (HavenVm) -> HavenVm) {
        _vms.value = _vms.value.map { if (it.id == vmId) transform(it) else it }
    }

    companion object {
        internal fun detectPackageManager(rootfs: File): String? = when {
            File(rootfs, "sbin/apk").exists() || File(rootfs, "usr/sbin/apk").exists() -> "apk"
            File(rootfs, "usr/bin/apt-get").exists() || File(rootfs, "bin/apt-get").exists() -> "apt"
            File(rootfs, "usr/bin/pacman").exists() || File(rootfs, "bin/pacman").exists() -> "pacman"
            File(rootfs, "usr/bin/xbps-install").exists() || File(rootfs, "bin/xbps-install").exists() -> "xbps"
            else -> null
        }

        fun createFailureReason(
            prootInstalled: Boolean = true,
            distroExists: Boolean = true,
            distroDownloaded: Boolean = true,
            rootfsReady: Boolean = true,
            downloadError: String? = null
        ): String {
            if (!prootInstalled) return "PRoot 未安装"
            if (!distroExists) return "未知发行版"
            if (!distroDownloaded) return downloadError?.takeIf { it.isNotBlank() } ?: "发行版未下载"
            if (!rootfsReady) return "rootfs 为空"
            return "创建虚拟机失败"
        }
    }
}

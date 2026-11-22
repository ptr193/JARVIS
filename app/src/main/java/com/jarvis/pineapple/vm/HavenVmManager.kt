package com.jarvis.pineapple.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 基于 PRoot 的 Linux 虚拟机管理器。
 *
 * 每个实例拥有独立 rootfs 和持久化登记。同一实例的操作串行执行，
 * 不同实例可并发；生产运行时仍由 [ProotManager] 提供真实进程能力。
 */
class HavenVmManager(
    private val prootManager: ProotManager,
    private val distroManager: VmDistroManager,
    private val runtime: VmRuntime = ProotVmRuntime(prootManager),
    private val instancesDir: File = File(prootManager.rootfsRoot().parentFile, "vm_instances"),
    private val asRoot: () -> Boolean = { true }
) {

    private val _vms = MutableStateFlow<List<HavenVm>>(emptyList())
    val vms: StateFlow<List<HavenVm>> = _vms.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val creationMutex = Mutex()
    private val vmLocks = ConcurrentHashMap<String, Mutex>()
    private val vmStateLock = Any()

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
        val ownsRootfs: Boolean = true
    )

    enum class HavenVmState {
        STOPPED, STARTING, RUNNING, STOPPING, PAUSED, ERROR
    }

    data class CreateVmResult(
        val vm: HavenVm? = null,
        val error: String? = null
    ) {
        val isSuccess: Boolean get() = vm != null
    }

    init {
        loadRegistry()
    }

    // ==================== 生命周期 ====================

    /** 创建独立 rootfs 副本；复制或登记失败时不留下可见虚拟机。 */
    suspend fun createVm(name: String, distroId: String): CreateVmResult = creationMutex.withLock {
        _isCreating.value = true
        try {
            if (!runtime.isProotInstalled()) return@withLock createError("PRoot 未安装")
            val distro = distroManager.findDistro(distroId)
                ?: return@withLock createError(createFailureReason(distroExists = false))
            if (!distro.isDownloaded && !distroManager.downloadDistro(distroId)) {
                val progress = distroManager.getDownloadProgress(distroId)
                return@withLock createError(
                    createFailureReason(distroDownloaded = false, downloadError = progress?.errorMessage)
                )
            }
            val source = distroManager.rootfsDir(distroId).canonicalFile
            if (!source.isDirectory || source.listFiles().isNullOrEmpty()) {
                return@withLock createError(createFailureReason(rootfsReady = false))
            }

            val id = "vm_${UUID.randomUUID()}"
            val instanceDir = instanceDir(id)
            val rootfs = File(instanceDir, "rootfs")
            val vm = HavenVm(
                id = id,
                name = name.ifBlank { distro.name },
                distroId = distroId,
                distroName = distro.name,
                packageManager = distro.packageManager,
                rootfsPath = rootfs.absolutePath
            )
            try {
                if (!instanceDir.mkdirs() && !instanceDir.isDirectory) {
                    throw IOException("无法创建虚拟机目录: ${instanceDir.absolutePath}")
                }
                runtime.copyRootfs(source, rootfs)
                if (!rootfs.isDirectory || rootfs.listFiles().isNullOrEmpty()) {
                    throw IOException("rootfs 副本为空")
                }
                if (!persistVm(vm)) throw IOException(_lastError.value ?: "虚拟机登记保存失败")
                synchronized(vmStateLock) { _vms.value = _vms.value + vm }
                _lastError.value = null
                CreateVmResult(vm = vm)
            } catch (e: Exception) {
                if (instanceDir.exists() && !instanceDir.deleteRecursively()) {
                    _lastError.value = "创建失败且临时目录清理失败: ${instanceDir.absolutePath}"
                }
                createError("创建虚拟机失败: ${e.message ?: e.toString()}")
            }
        } catch (e: Exception) {
            createError("创建虚拟机失败: ${e.message ?: e.toString()}")
        } finally {
            _isCreating.value = false
        }
    }

    /** STOPPED/ERROR 可启动；RUNNING 重复启动幂等，其余状态拒绝。 */
    suspend fun startVm(vmId: String): Boolean = runVmOperation(vmId) { startVmLocked(it) }

    private suspend fun startVmLocked(vm: HavenVm): Boolean {
        if (vm.state == HavenVmState.RUNNING) {
            _lastError.value = null
            return true
        }
        if (vm.state != HavenVmState.STOPPED && vm.state != HavenVmState.ERROR) {
            return fail("虚拟机当前状态 ${vm.state} 不允许启动")
        }
        _lastError.value = null
        if (!updateVmLocked(vm.id) { it.copy(state = HavenVmState.STARTING) }) return false

        return try {
            val config = configFor(vm)
            val probe = runtime.executeCommand(config, "echo JARVIS_VM_OK")
            if (probe.exitCode == 0 && probe.output.contains("JARVIS_VM_OK") && runtime.startSession(config)) {
                val running = HavenVmState.RUNNING
                if (!updateVmLocked(vm.id) {
                        it.copy(state = running, lastStartedAt = System.currentTimeMillis())
                    }
                ) {
                    runtime.stopSession(config)
                    restoreVmLocked(vm)
                    return fail("虚拟机已启动，但运行状态保存失败，已停止会话")
                }
                // 启动成功与软件包列表刷新失败必须分开报告。
                refreshInstalledPackagesLocked(vm.id)
                true
            } else {
                runtime.stopSession(config)
                updateVmLocked(vm.id) { it.copy(state = HavenVmState.ERROR) }
                fail("虚拟机启动失败: ${probe.output.ifBlank { "PRoot 会话不可用" }.take(300)}")
            }
        } catch (e: Exception) {
            runtime.stopSession(configFor(vm))
            updateVmLocked(vm.id) { it.copy(state = HavenVmState.ERROR) }
            fail("虚拟机启动失败: ${e.message ?: e.toString()}")
        }
    }

    /** 停止主会话与桌面后台进程；停止失败恢复操作前状态。 */
    suspend fun stopVm(vmId: String): Boolean = runVmOperation(vmId) { stopVmLocked(it) }

    private suspend fun stopVmLocked(vm: HavenVm): Boolean {
        if (vm.state == HavenVmState.STOPPED) {
            _lastError.value = null
            return true
        }
        if (vm.state !in setOf(HavenVmState.RUNNING, HavenVmState.PAUSED, HavenVmState.ERROR)) {
            return fail("虚拟机当前状态 ${vm.state} 不允许停止")
        }
        _lastError.value = null
        if (!updateVmLocked(vm.id) { it.copy(state = HavenVmState.STOPPING) }) return false

        if (!stopDesktopBackgroundsLocked(vm.id)) {
            updateVmLocked(vm.id) { vm }
            return fail("桌面后台进程停止失败，虚拟机保持 ${vm.state} 状态")
        }
        val stopped = try {
            runtime.stopSession(configFor(vm))
        } catch (e: Exception) {
            _lastError.value = "停止会话异常: ${e.message ?: e.toString()}"
            false
        }
        if (!stopped) {
            updateVmLocked(vm.id) { vm }
            return fail("PRoot 会话停止失败，虚拟机恢复 ${vm.state} 状态")
        }
        if (!updateVmLocked(vm.id) { it.copy(state = HavenVmState.STOPPED) }) {
            return fail("会话已停止，但停止状态保存失败")
        }
        _lastError.value = null
        return true
    }

    suspend fun pauseVm(vmId: String): Boolean = runVmOperation(vmId) { vm ->
        if (vm.state != HavenVmState.RUNNING) return@runVmOperation fail("仅运行中的虚拟机可以暂停")
        val paused = runtime.pauseSession(configFor(vm))
        if (!paused) return@runVmOperation fail("暂停失败：无法取得进程 PID 或会话未运行")
        updateVmLocked(vm.id) { it.copy(state = HavenVmState.PAUSED) }
    }

    suspend fun resumeVm(vmId: String): Boolean = runVmOperation(vmId) { vm ->
        if (vm.state != HavenVmState.PAUSED) return@runVmOperation fail("仅已暂停的虚拟机可以恢复")
        val resumed = runtime.resumeSession(configFor(vm))
        if (!resumed) return@runVmOperation fail("恢复失败：会话未运行或信号发送失败")
        updateVmLocked(vm.id) { it.copy(state = HavenVmState.RUNNING) }
    }

    suspend fun rebootVm(vmId: String): Boolean = runVmOperation(vmId) { vm ->
        if (vm.state != HavenVmState.RUNNING && vm.state != HavenVmState.PAUSED) {
            return@runVmOperation fail("仅运行中或已暂停的虚拟机可以重启")
        }
        if (!stopVmLocked(vm)) return@runVmOperation false
        val stopped = findVm(vm.id) ?: return@runVmOperation fail("虚拟机登记丢失")
        startVmLocked(stopped)
    }

    /** 停止全部相关进程后删除独立 rootfs、快照和登记。 */
    suspend fun deleteVm(vmId: String): Boolean {
        val lock = lockFor(vmId)
        return lock.withLock {
            val vm = findVm(vmId) ?: return@withLock fail("虚拟机不存在")
            try {
                val stopped = if (vm.state == HavenVmState.STOPPED) {
                    prepareStoppedVmLocked(vm)
                } else {
                    stopVmLocked(vm)
                }
                if (!stopped) return@withLock false

                val instance = instanceDir(vm.id).canonicalFile
                val instancesRoot = instancesDir.canonicalFile
                if (!isWithin(instance, instancesRoot)) return@withLock fail("虚拟机目录越界，拒绝删除")
                val rootfs = File(vm.rootfsPath).canonicalFile
                if (!isWithin(rootfs, instance)) {
                    if (!vm.ownsRootfs) return@withLock fail("共享 rootfs 实例缺少独立目录，拒绝删除登记")
                    val managedRoot = prootManager.rootfsRoot().canonicalFile
                    if (!isWithin(rootfs, managedRoot)) return@withLock fail("rootfs 不在受管目录内，拒绝删除")
                    if (rootfs.exists() && !rootfs.deleteRecursively()) {
                        return@withLock fail("rootfs 删除失败: ${rootfs.absolutePath}")
                    }
                }
                if (instance.exists() && !instance.deleteRecursively()) {
                    return@withLock fail("虚拟机目录删除失败，rootfs、快照或登记仍有残留")
                }
                synchronized(vmStateLock) { _vms.value = _vms.value.filterNot { it.id == vmId } }
                vmLocks.remove(vmId, lock)
                _lastError.value = null
                true
            } catch (e: Exception) {
                fail("删除虚拟机失败: ${e.message ?: e.toString()}")
            }
        }
    }

    /** 将已解压且位于应用 rootfs 目录内的系统登记为独立虚拟机。 */
    suspend fun createImportedVm(name: String, rootfsPath: String): CreateVmResult = creationMutex.withLock {
        _isCreating.value = true
        try {
            if (!runtime.isProotInstalled()) return@withLock createError("PRoot 未安装")
            val rootfs = File(rootfsPath).canonicalFile
            val managedRoot = prootManager.rootfsRoot().canonicalFile
            val packageManager = detectPackageManager(rootfs)
            when {
                !isWithin(rootfs, managedRoot) -> createError("导入目录不在应用 rootfs 中")
                !rootfs.isDirectory || rootfs.listFiles().isNullOrEmpty() -> createError("rootfs 为空")
                packageManager == null -> createError("无法识别 rootfs 的包管理器")
                _vms.value.any { File(it.rootfsPath).canonicalFile == rootfs } ->
                    createError("该 rootfs 已登记，不能由多个虚拟机共同拥有")
                else -> {
                    val id = "vm_imported_${UUID.randomUUID()}"
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
                    val instance = instanceDir(id)
                    if (!instance.mkdirs() && !instance.isDirectory) {
                        createError("无法创建虚拟机登记目录")
                    } else if (!persistVm(vm)) {
                        createError(_lastError.value ?: "虚拟机登记保存失败")
                    } else {
                        synchronized(vmStateLock) { _vms.value = _vms.value + vm }
                        _lastError.value = null
                        CreateVmResult(vm = vm)
                    }
                }
            }
        } catch (e: Exception) {
            createError("导入虚拟机失败: ${e.message ?: e.toString()}")
        } finally {
            _isCreating.value = false
        }
    }

    // ==================== 命令 / 软件包 ====================

    suspend fun executeCommand(vmId: String, command: String): String =
        executeCommandResult(vmId, command)?.output ?: "Error: 虚拟机不存在"

    suspend fun executeCommandResult(
        vmId: String,
        command: String,
        workingDir: String? = null
    ): ProotManager.CommandResult? {
        val lock = lockFor(vmId)
        return lock.withLock {
            val vm = findVm(vmId) ?: return@withLock null
            if (vm.state != HavenVmState.RUNNING) {
                return@withLock ProotManager.CommandResult(
                    command,
                    "Error: 虚拟机未运行（${vm.state}）",
                    -1,
                    0
                ).also { _lastError.value = it.output }
            }
            try {
                val baseConfig = configFor(vm)
                val config = if (workingDir == null) baseConfig else baseConfig.copy(workingDir = workingDir)
                runtime.executeCommand(config, command)
            } catch (e: Exception) {
                val message = "Error: 命令执行异常: ${e.message ?: e.toString()}"
                _lastError.value = message
                ProotManager.CommandResult(command, message, -1, 0)
            }
        }
    }

    suspend fun installPackage(vmId: String, packageName: String): Boolean = runVmOperation(vmId) { vm ->
        if (!requireRunning(vm)) return@runVmOperation false
        val ok = runtime.installPackage(configFor(vm), vm.packageManager, packageName)
        if (ok) refreshInstalledPackagesLocked(vmId) else fail("软件包安装失败")
        ok
    }

    suspend fun uninstallPackage(vmId: String, packageName: String): Boolean = runVmOperation(vmId) { vm ->
        if (!requireRunning(vm)) return@runVmOperation false
        val ok = runtime.uninstallPackage(configFor(vm), vm.packageManager, packageName)
        if (ok) refreshInstalledPackagesLocked(vmId) else fail("软件包卸载失败")
        ok
    }

    suspend fun updatePackages(vmId: String): Boolean = runVmOperation(vmId) { vm ->
        if (!requireRunning(vm)) return@runVmOperation false
        val ok = runtime.updatePackages(configFor(vm), vm.packageManager)
        if (!ok) fail("软件包更新失败")
        ok
    }

    suspend fun refreshInstalledPackages(vmId: String): Boolean = runVmOperation(vmId) { vm ->
        if (!requireRunning(vm)) return@runVmOperation false
        refreshInstalledPackagesLocked(vmId)
    }

    private suspend fun refreshInstalledPackagesLocked(vmId: String): Boolean {
        val vm = findVm(vmId) ?: return fail("虚拟机不存在")
        val result = runtime.listInstalledPackages(configFor(vm), vm.packageManager)
        if (result.exitCode != 0) return fail("软件包列表刷新失败: ${result.output.take(300)}")
        val packages = result.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return updateVmLocked(vmId) { it.copy(installedPackages = packages) }
    }

    // ==================== 查询 / 桌面 / 快照 ====================

    fun findVm(vmId: String): HavenVm? = _vms.value.firstOrNull { it.id == vmId }
    fun getVmStatus(vmId: String): HavenVm? = findVm(vmId)
    fun getRunningVms(): List<HavenVm> = _vms.value.filter { it.state == HavenVmState.RUNNING }
    fun getInstalledPackages(vmId: String): List<String> = findVm(vmId)?.installedPackages ?: emptyList()

    fun configFor(vm: HavenVm): ProotManager.ProotConfig {
        val root = asRoot()
        return ProotManager.ProotConfig(
            rootfsPath = vm.rootfsPath,
            workingDir = if (root) "/root" else "/tmp",
            asRoot = root
        )
    }

    fun isRootEnabled(): Boolean = asRoot()

    suspend fun installDesktopEnvironment(vmId: String): Boolean = runVmOperation(vmId) { vm ->
        if (!requireRunning(vm)) return@runVmOperation false
        val command = when (vm.packageManager) {
            "apk" -> "apk add --no-cache xvfb x11vnc xfce4 xfce4-terminal dbus"
            "apt" -> "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y xvfb x11vnc xfce4 xfce4-terminal dbus-x11"
            "pacman" -> "pacman -Sy --noconfirm xorg-server-xvfb x11vnc xfce4 xfce4-terminal dbus"
            "xbps" -> "xbps-install -y xvfb x11vnc xfce4 xfce4-terminal dbus"
            else -> return@runVmOperation fail("不支持的包管理器: ${vm.packageManager}")
        }
        val result = runtime.executeCommand(configFor(vm), command)
        if (result.exitCode != 0) fail("桌面环境安装失败: ${result.output.take(300)}")
        result.exitCode == 0
    }

    internal suspend fun launchBackground(vmId: String, key: String, command: String): Boolean =
        runVmOperation(vmId) { vm ->
            if (!requireRunning(vm)) return@runVmOperation false
            val launched = runtime.launchBackground(configFor(vm), key, command)
            if (!launched) fail("后台进程启动失败: $key")
            launched
        }

    internal suspend fun stopDesktop(vmId: String): Boolean {
        val lock = lockFor(vmId)
        return lock.withLock {
            if (findVm(vmId) == null) return@withLock fail("虚拟机不存在")
            stopDesktopBackgroundsLocked(vmId)
        }
    }

    internal fun snapshotsDir(vmId: String): File = File(instanceDir(vmId), "snapshots")
    internal fun operationLockFor(vmId: String): Mutex = lockFor(vmId)

    /** 由快照管理器在同一实例锁内调用，确保无会话或桌面进程继续写 rootfs。 */
    internal suspend fun prepareStoppedVm(vmId: String): Boolean {
        val vm = findVm(vmId) ?: return fail("虚拟机不存在")
        return prepareStoppedVmLocked(vm)
    }

    private suspend fun prepareStoppedVmLocked(vm: HavenVm): Boolean {
        if (vm.state != HavenVmState.STOPPED) return fail("快照操作仅允许在虚拟机停止后执行")
        if (!stopDesktopBackgroundsLocked(vm.id)) return fail("桌面后台进程停止失败，禁止快照操作")
        if (!runtime.stopSession(configFor(vm))) return fail("残留 PRoot 会话停止失败，禁止快照操作")
        return true
    }

    private suspend fun stopDesktopBackgroundsLocked(vmId: String): Boolean {
        val desktopStopped = try {
            runtime.stopBackground("desktop-$vmId")
        } catch (e: Exception) {
            _lastError.value = "停止桌面失败: ${e.message ?: e.toString()}"
            false
        }
        val browserStopped = try {
            runtime.stopBackground("desktop-$vmId-browser")
        } catch (e: Exception) {
            _lastError.value = "停止浏览器失败: ${e.message ?: e.toString()}"
            false
        }
        return desktopStopped && browserStopped
    }

    private fun requireRunning(vm: HavenVm): Boolean =
        if (vm.state == HavenVmState.RUNNING) true else fail("该操作要求虚拟机处于 RUNNING 状态")

    private suspend fun runVmOperation(vmId: String, block: suspend (HavenVm) -> Boolean): Boolean {
        val lock = lockFor(vmId)
        return lock.withLock {
            val vm = findVm(vmId) ?: return@withLock fail("虚拟机不存在")
            try {
                block(vm)
            } catch (e: Exception) {
                fail("虚拟机操作失败: ${e.message ?: e.toString()}")
            }
        }
    }

    private fun lockFor(vmId: String): Mutex {
        val existing = vmLocks[vmId]
        if (existing != null) return existing
        val created = Mutex()
        return vmLocks.putIfAbsent(vmId, created) ?: created
    }

    private fun updateVmLocked(vmId: String, transform: (HavenVm) -> HavenVm): Boolean {
        val current = findVm(vmId) ?: return fail("虚拟机不存在")
        val updated = transform(current)
        if (!persistVm(updated)) return false
        synchronized(vmStateLock) {
            _vms.value = _vms.value.map { if (it.id == vmId) updated else it }
        }
        return true
    }

    private fun restoreVmLocked(vm: HavenVm) {
        if (persistVm(vm)) {
            synchronized(vmStateLock) {
                _vms.value = _vms.value.map { if (it.id == vm.id) vm else it }
            }
        }
    }

    // ==================== 登记持久化 ====================

    private fun instanceDir(vmId: String): File = File(instancesDir, vmId)
    private fun metadataFile(vmId: String): File = File(instanceDir(vmId), "vm.properties")

    private fun persistVm(vm: HavenVm): Boolean {
        val directory = instanceDir(vm.id)
        val target = metadataFile(vm.id)
        val temporary = File(directory, "vm.properties.tmp")
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建登记目录")
            val properties = Properties()
            properties.setProperty("id", vm.id)
            properties.setProperty("name", vm.name)
            properties.setProperty("distroId", vm.distroId)
            properties.setProperty("distroName", vm.distroName)
            properties.setProperty("packageManager", vm.packageManager)
            properties.setProperty("rootfsPath", vm.rootfsPath)
            properties.setProperty("state", vm.state.name)
            properties.setProperty("installedPackages", vm.installedPackages.joinToString("\n"))
            properties.setProperty("createdAt", vm.createdAt.toString())
            properties.setProperty("lastStartedAt", vm.lastStartedAt.toString())
            properties.setProperty("ownsRootfs", vm.ownsRootfs.toString())
            FileOutputStream(temporary).use { properties.store(it, null) }
            if (!temporary.renameTo(target)) throw IOException("无法原子替换虚拟机登记")
            true
        } catch (e: Exception) {
            _lastError.value = "虚拟机登记保存失败: ${e.message ?: e.toString()}"
            false
        } finally {
            temporary.delete()
        }
    }

    private fun loadRegistry() {
        try {
            if (!instancesDir.isDirectory && !instancesDir.mkdirs()) {
                throw IOException("无法创建虚拟机登记目录: ${instancesDir.absolutePath}")
            }
            val directories = instancesDir.listFiles { file -> file.isDirectory }
                ?: throw IOException("无法读取虚拟机登记目录")
            val restored = mutableListOf<HavenVm>()
            val errors = mutableListOf<String>()
            directories.forEach { directory ->
                val metadata = File(directory, "vm.properties")
                if (!metadata.isFile) return@forEach
                try {
                    val properties = Properties()
                    metadata.inputStream().use { properties.load(it) }
                    fun required(key: String): String = properties.getProperty(key)
                        ?.takeIf { it.isNotBlank() }
                        ?: throw IOException("登记缺少字段 $key")
                    val id = required("id")
                    if (id != directory.name) throw IOException("登记 ID 与目录不一致")
                    val rootfs = File(required("rootfsPath")).canonicalFile
                    val managedRoot = prootManager.rootfsRoot().canonicalFile
                    if (!isWithin(rootfs, instancesDir.canonicalFile) && !isWithin(rootfs, managedRoot)) {
                        throw IOException("rootfs 路径越界")
                    }
                    val savedState = HavenVmState.valueOf(required("state"))
                    // 新进程没有可恢复的运行时会话，不得把旧 RUNNING/PAUSED 当成存活进程。
                    val state = if (savedState == HavenVmState.STOPPED || savedState == HavenVmState.ERROR) {
                        savedState
                    } else {
                        HavenVmState.STOPPED
                    }
                    val vm = HavenVm(
                        id = id,
                        name = required("name"),
                        distroId = required("distroId"),
                        distroName = required("distroName"),
                        packageManager = required("packageManager"),
                        rootfsPath = rootfs.absolutePath,
                        state = state,
                        installedPackages = properties.getProperty("installedPackages").orEmpty()
                            .lines().map { it.trim() }.filter { it.isNotEmpty() },
                        createdAt = required("createdAt").toLong(),
                        lastStartedAt = properties.getProperty("lastStartedAt")?.toLongOrNull() ?: 0L,
                        ownsRootfs = properties.getProperty("ownsRootfs")?.toBooleanStrictOrNull() ?: true
                    )
                    restored += vm
                    if (state != savedState && !persistVm(vm)) {
                        errors += "$id: 运行状态复位保存失败"
                    }
                } catch (e: Exception) {
                    errors += "${directory.name}: ${e.message ?: e.toString()}"
                }
            }
            _vms.value = restored.sortedBy { it.createdAt }
            _lastError.value = if (errors.isEmpty()) null else "部分虚拟机登记未恢复: ${errors.joinToString("；")}"
        } catch (e: Exception) {
            _vms.value = emptyList()
            _lastError.value = "虚拟机登记恢复失败: ${e.message ?: e.toString()}"
        }
    }

    private fun isWithin(file: File, parent: File): Boolean =
        file.path.startsWith(parent.path + File.separator)

    private fun createError(message: String): CreateVmResult {
        _lastError.value = message
        return CreateVmResult(error = message)
    }

    private fun fail(message: String): Boolean {
        _lastError.value = message
        return false
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

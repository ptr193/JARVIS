package com.jarvis.pineapple.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 软件包信息
 */
data class PackageInfo(
    val name: String,
    val version: String = "",
    val description: String = "",
    val installed: Boolean = false,
    val sizeBytes: Long = 0,
    val category: String = "uncategorized"
)

/**
 * 软件包操作结果
 */
sealed class PackageOperationResult {
    data class Success(val message: String) : PackageOperationResult()
    data class Failure(val error: String) : PackageOperationResult()
    data class NeedRoot(val message: String) : PackageOperationResult()
}

/**
 * 虚拟机软件包管理器。
 *
 * 通过 [ProotManager] 在目标 rootfs 中执行真实的包管理器命令
 * （apk / apt / pacman / xbps），并维护已安装列表与操作日志。
 */
class VmPackageManager(
    private val vmId: String,
    private val rootfsPath: String,
    private val prootManager: ProotManager,
    private val packageManager: String = "apt"
) {
    private val _packages = MutableStateFlow<List<PackageInfo>>(emptyList())
    val packages: StateFlow<List<PackageInfo>> = _packages.asStateFlow()

    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    private val _operationLog = MutableStateFlow<List<String>>(emptyList())
    val operationLog: StateFlow<List<String>> = _operationLog.asStateFlow()

    private fun config(useRoot: Boolean = true): ProotManager.ProotConfig =
        ProotManager.ProotConfig(rootfsPath = rootfsPath, asRoot = useRoot)

    /**
     * 刷新已安装软件包列表（真实查询 guest）。
     */
    suspend fun refreshPackageList() {
        _isOperating.value = true
        try {
            val names = prootManager.listInstalledPackages(config(), packageManager)
            _packages.value = names.map { PackageInfo(name = it, installed = true) }
        } finally {
            _isOperating.value = false
        }
    }

    /**
     * 搜索可用软件包（真实查询远端索引）。
     */
    suspend fun searchPackages(query: String): List<PackageInfo> {
        val command = when (packageManager.lowercase()) {
            "apk" -> "apk search -v '$query'"
            "apt" -> "apt-cache search '$query'"
            "pacman" -> "pacman -Ss '$query'"
            "xbps" -> "xbps-query -Rs '$query'"
            else -> return emptyList()
        }
        _isOperating.value = true
        addLog("搜索软件包: $query")
        return try {
            val result = prootManager.executeCommand(config(), command)
            parseSearchResult(result.output).also { _packages.value = it }
        } finally {
            _isOperating.value = false
        }
    }

    private fun parseSearchResult(output: String): List<PackageInfo> {
        if (output.isBlank()) return emptyList()
        return output.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                when (packageManager.lowercase()) {
                    "apk" -> {
                        // <name>-<version> <description>
                        val parts = line.split(Regex("\\s+"), limit = 2)
                        if (parts.isEmpty()) null
                        else PackageInfo(name = parts[0], description = parts.getOrElse(1) { "" })
                    }
                    "apt" -> {
                        val parts = line.split(" - ", limit = 2)
                        if (parts.isEmpty()) null
                        else PackageInfo(name = parts[0].trim(), description = parts.getOrElse(1) { "" })
                    }
                    "pacman" -> {
                        // "repo/name version\n    description"
                        if (line.contains("/")) {
                            val after = line.substringAfter("/")
                            val name = after.substringBefore(" ").trim()
                            PackageInfo(name = name)
                        } else null
                    }
                    "xbps" -> {
                        val name = line.substringBefore(" ").substringAfterLast("/").trim()
                        if (name.isEmpty()) null else PackageInfo(name = name)
                    }
                    else -> null
                }
            }
            .distinctBy { it.name }
            .take(200)
    }

    suspend fun installPackage(packageName: String, useRoot: Boolean = true): PackageOperationResult {
        _isOperating.value = true
        addLog("正在安装 $packageName ...")
        return try {
            val ok = prootManager.installPackage(config(useRoot), packageManager, packageName)
            if (ok) {
                addLog("$packageName 安装成功")
                PackageOperationResult.Success("$packageName 安装成功")
            } else {
                addLog("$packageName 安装失败")
                PackageOperationResult.Failure("$packageName 安装失败")
            }
        } catch (e: Exception) {
            PackageOperationResult.Failure("安装失败: ${e.message}")
        } finally {
            _isOperating.value = false
        }
    }

    suspend fun uninstallPackage(packageName: String, useRoot: Boolean = true): PackageOperationResult {
        _isOperating.value = true
        addLog("正在卸载 $packageName ...")
        return try {
            val ok = prootManager.uninstallPackage(config(useRoot), packageManager, packageName)
            if (ok) {
                addLog("$packageName 卸载成功")
                PackageOperationResult.Success("$packageName 卸载成功")
            } else {
                PackageOperationResult.Failure("$packageName 卸载失败")
            }
        } catch (e: Exception) {
            PackageOperationResult.Failure("卸载失败: ${e.message}")
        } finally {
            _isOperating.value = false
        }
    }

    suspend fun updatePackage(packageName: String, useRoot: Boolean = true): PackageOperationResult {
        _isOperating.value = true
        addLog("正在更新 $packageName ...")
        return try {
            val ok = prootManager.installPackage(config(useRoot), packageManager, packageName)
            if (ok) PackageOperationResult.Success("$packageName 更新成功")
            else PackageOperationResult.Failure("$packageName 更新失败")
        } catch (e: Exception) {
            PackageOperationResult.Failure("更新失败: ${e.message}")
        } finally {
            _isOperating.value = false
        }
    }

    suspend fun updateAllPackages(useRoot: Boolean = true): PackageOperationResult {
        _isOperating.value = true
        addLog("正在更新所有包 ...")
        return try {
            val ok = prootManager.updatePackages(config(useRoot), packageManager)
            if (ok) {
                addLog("所有包已更新")
                PackageOperationResult.Success("所有包更新成功")
            } else {
                PackageOperationResult.Failure("批量更新失败")
            }
        } catch (e: Exception) {
            PackageOperationResult.Failure("批量更新失败: ${e.message}")
        } finally {
            _isOperating.value = false
        }
    }

    fun isInstalled(packageName: String): Boolean =
        _packages.value.any { it.name == packageName && it.installed }

    fun getInstalledPackages(): List<PackageInfo> = _packages.value.filter { it.installed }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _operationLog.value = _operationLog.value + "[$timestamp] $message"
    }
}

/**
 * Linux 发行版类型
 */
enum class DistroType(val packageManager: String) {
    DEBIAN("apt"), UBUNTU("apt"), FEDORA("dnf"), ARCH("pacman"), ALPINE("apk")
}

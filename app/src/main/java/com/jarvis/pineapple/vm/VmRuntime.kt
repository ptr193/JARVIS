package com.jarvis.pineapple.vm

import java.io.File

/** PRoot 运行时边界；测试替身只用于验证虚拟机状态与调用顺序。 */
interface VmRuntime {
    fun isProotInstalled(): Boolean
    suspend fun executeCommand(config: ProotManager.ProotConfig, command: String): ProotManager.CommandResult
    suspend fun startSession(config: ProotManager.ProotConfig): Boolean
    suspend fun stopSession(config: ProotManager.ProotConfig): Boolean
    suspend fun pauseSession(config: ProotManager.ProotConfig): Boolean
    suspend fun resumeSession(config: ProotManager.ProotConfig): Boolean
    suspend fun installPackage(config: ProotManager.ProotConfig, packageManager: String, packageName: String): Boolean
    suspend fun uninstallPackage(config: ProotManager.ProotConfig, packageManager: String, packageName: String): Boolean
    suspend fun updatePackages(config: ProotManager.ProotConfig, packageManager: String): Boolean
    suspend fun listInstalledPackages(config: ProotManager.ProotConfig, packageManager: String): ProotManager.CommandResult
    suspend fun stopBackground(key: String): Boolean
    suspend fun launchBackground(config: ProotManager.ProotConfig, key: String, command: String): Boolean
    fun copyRootfs(source: File, target: File)
}

class ProotVmRuntime(private val prootManager: ProotManager) : VmRuntime {
    override fun isProotInstalled(): Boolean = prootManager.isProotInstalled()
    override suspend fun executeCommand(config: ProotManager.ProotConfig, command: String) =
        prootManager.executeCommand(config, command)
    override suspend fun startSession(config: ProotManager.ProotConfig) = prootManager.startSession(config)
    override suspend fun stopSession(config: ProotManager.ProotConfig) = prootManager.stopSession(config)
    override suspend fun pauseSession(config: ProotManager.ProotConfig) = prootManager.pauseSession(config)
    override suspend fun resumeSession(config: ProotManager.ProotConfig) = prootManager.resumeSession(config)
    override suspend fun installPackage(
        config: ProotManager.ProotConfig,
        packageManager: String,
        packageName: String
    ) = prootManager.installPackage(config, packageManager, packageName)
    override suspend fun uninstallPackage(
        config: ProotManager.ProotConfig,
        packageManager: String,
        packageName: String
    ) = prootManager.uninstallPackage(config, packageManager, packageName)
    override suspend fun updatePackages(config: ProotManager.ProotConfig, packageManager: String) =
        prootManager.updatePackages(config, packageManager)
    override suspend fun listInstalledPackages(config: ProotManager.ProotConfig, packageManager: String) =
        prootManager.listInstalledPackages(config, packageManager)
    override suspend fun stopBackground(key: String) = prootManager.stopBackground(key)
    override suspend fun launchBackground(config: ProotManager.ProotConfig, key: String, command: String) =
        prootManager.launchBackground(config, key, command)
    override fun copyRootfs(source: File, target: File) = prootManager.copyRootfs(source, target)
}

package com.jarvis.pineapple.vm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 多平台虚拟机管理器（REQ-08）。
 *
 * 支持 Windows、macOS、iOS 等平台的虚拟机管理。
 * 各平台差异在列表中明确标注兼容性与限制。
 */
class VmPlatformManager(private val context: Context) {

    /**
     * 平台类型
     */
    enum class PlatformType {
        LINUX,          // Linux 全系（已支持）
        WINDOWS,        // Windows x86_64/ARM64
        MACOS,          // macOS (Apple Silicon/Intel)
        IOS,            // iOS（暂不支持）
        ANDROID         // Android (当前平台)
    }

    /**
     * 平台信息
     */
    data class PlatformInfo(
        val type: PlatformType,
        val name: String,
        val description: String,
        val isSupported: Boolean,
        val compatibility: CompatibilityLevel,
        val requirements: PlatformRequirements,
        val limitations: List<String>
    )

    enum class CompatibilityLevel {
        FULL,       // 完全支持
        PARTIAL,    // 部分支持
        EXPERIMENTAL, // 实验性支持
        UNSUPPORTED  // 不支持
    }

    data class PlatformRequirements(
        val minMemoryMb: Int,
        val minStorageGb: Int,
        val requiredFeatures: List<String>,
        val recommendedCpuCores: Int
    )

    /**
     * 平台适配器
     */
    interface PlatformAdapter {
        fun createVm(config: VmConfig): Boolean
        fun startVm(vmId: String): Boolean
        fun stopVm(vmId: String): Boolean
        fun deleteVm(vmId: String): Boolean
        fun executeCommand(vmId: String, command: String): CommandResult
        fun transferFile(vmId: String, localPath: String, remotePath: String): Boolean
    }

    private val _currentPlatform = MutableStateFlow(PlatformType.ANDROID)
    val currentPlatform: StateFlow<PlatformType> = _currentPlatform.asStateFlow()

    private val _adapters = mutableMapOf<PlatformType, PlatformAdapter>()

    /**
     * 注册平台适配器
     */
    fun registerAdapter(platform: PlatformType, adapter: PlatformAdapter) {
        _adapters[platform] = adapter
    }

    /**
     * 获取平台信息
     */
    fun getPlatformInfo(platform: PlatformType): PlatformInfo {
        return when (platform) {
            PlatformType.LINUX -> PlatformInfo(
                type = PlatformType.LINUX,
                name = "Linux",
                description = "Linux 全系发行版",
                isSupported = true,
                compatibility = CompatibilityLevel.FULL,
                requirements = PlatformRequirements(
                    minMemoryMb = 512,
                    minStorageGb = 5,
                    requiredFeatures = listOf("proot", "kernel 3.0+"),
                    recommendedCpuCores = 2
                ),
                limitations = listOf("部分系统调用受限", "需要 root 权限获取完整功能")
            )

            PlatformType.WINDOWS -> PlatformInfo(
                type = PlatformType.WINDOWS,
                name = "Windows",
                description = "Windows x86_64 / ARM64",
                isSupported = false,
                compatibility = CompatibilityLevel.UNSUPPORTED,
                requirements = PlatformRequirements(
                    minMemoryMb = 2048,
                    minStorageGb = 40,
                    requiredFeatures = listOf("Windows 10+", "Hyper-V 可选", "WSL2 可选"),
                    recommendedCpuCores = 4
                ),
                limitations = listOf("当前版本不支持，需实现 Hyper-V/QEMU 后端", "需要 Windows 授权", "ARM64 支持有限")
            )

            PlatformType.MACOS -> PlatformInfo(
                type = PlatformType.MACOS,
                name = "macOS",
                description = "macOS (Apple Silicon / Intel)",
                isSupported = true,
                compatibility = CompatibilityLevel.PARTIAL,
                requirements = PlatformRequirements(
                    minMemoryMb = 4096,
                    minStorageGb = 60,
                    requiredFeatures = listOf("macOS 12+", "Apple Virtualization Framework 或 QEMU"),
                    recommendedCpuCores = 4
                ),
                limitations = listOf("需要 Apple 硬件或 macOS 许可", "iOS 应用无法直接运行", "部分系统功能受限")
            )

            PlatformType.IOS -> PlatformInfo(
                type = PlatformType.IOS,
                name = "iOS",
                description = "iOS（暂不支持）",
                isSupported = false,
                compatibility = CompatibilityLevel.UNSUPPORTED,
                requirements = PlatformRequirements(
                    minMemoryMb = 4096,
                    minStorageGb = 64,
                    requiredFeatures = listOf("iOS 15+", "开发者账号"),
                    recommendedCpuCores = 6
                ),
                limitations = listOf("iOS 系统限制严格", "无法直接运行虚拟机", "需要越狱或使用模拟器")
            )

            PlatformType.ANDROID -> PlatformInfo(
                type = PlatformType.ANDROID,
                name = "Android",
                description = "Android (当前平台)",
                isSupported = true,
                compatibility = CompatibilityLevel.FULL,
                requirements = PlatformRequirements(
                    minMemoryMb = 2048,
                    minStorageGb = 20,
                    requiredFeatures = listOf("Android 6+", "ARM64 或 x86_64"),
                    recommendedCpuCores = 2
                ),
                limitations = listOf("需要 root 权限获取完整功能", "部分系统调用受限")
            )
        }
    }

    /**
     * 获取所有支持的平台
     */
    fun getSupportedPlatforms(): List<PlatformInfo> {
        return PlatformType.values().map { getPlatformInfo(it) }
    }

    /**
     * 检查平台兼容性
     */
    fun checkCompatibility(platform: PlatformType, vmConfig: VmConfig): CompatibilityCheck {
        val info = getPlatformInfo(platform)

        val issues = mutableListOf<String>()

        if (!info.isSupported) {
            issues.add("平台 ${info.name} 当前不支持")
        }

        if (vmConfig.memoryMb < info.requirements.minMemoryMb) {
            issues.add("内存不足: 需要 ${info.requirements.minMemoryMb}MB，当前 ${vmConfig.memoryMb}MB")
        }

        if (vmConfig.diskGb < info.requirements.minStorageGb) {
            issues.add("磁盘不足: 需要 ${info.requirements.minStorageGb}GB，当前 ${vmConfig.diskGb}GB")
        }

        return CompatibilityCheck(
            isCompatible = issues.isEmpty(),
            platform = info,
            issues = issues
        )
    }

    data class CompatibilityCheck(
        val isCompatible: Boolean,
        val platform: PlatformInfo,
        val issues: List<String>
    )

    /**
     * 获取推荐配置
     */
    fun getRecommendedConfig(platform: PlatformType): VmConfig {
        val info = getPlatformInfo(platform)
        return VmConfig(
            vmId = "recommended_${platform.name.lowercase()}",
            name = "${info.name} 虚拟机",
            cpuCores = info.requirements.recommendedCpuCores,
            memoryMb = info.requirements.minMemoryMb,
            diskGb = info.requirements.minStorageGb,
            networkMode = NetworkMode.NAT
        )
    }

    /**
     * 创建虚拟机
     */
    fun createVm(platform: PlatformType, config: VmConfig): Boolean {
        val adapter = _adapters[platform] ?: return false
        return adapter.createVm(config)
    }

    /**
     * 启动虚拟机
     */
    fun startVm(platform: PlatformType, vmId: String): Boolean {
        val adapter = _adapters[platform] ?: return false
        return adapter.startVm(vmId)
    }

    /**
     * 停止虚拟机
     */
    fun stopVm(platform: PlatformType, vmId: String): Boolean {
        val adapter = _adapters[platform] ?: return false
        return adapter.stopVm(vmId)
    }

    /**
     * 删除虚拟机
     */
    fun deleteVm(platform: PlatformType, vmId: String): Boolean {
        val adapter = _adapters[platform] ?: return false
        return adapter.deleteVm(vmId)
    }

    /**
     * 获取平台使用统计
     */
    fun getPlatformStatistics(): PlatformStatistics {
        return PlatformStatistics(
            totalPlatforms = PlatformType.values().size,
            supportedPlatforms = PlatformType.values().count { getPlatformInfo(it).isSupported },
            activePlatforms = _adapters.keys.size
        )
    }

    data class PlatformStatistics(
        val totalPlatforms: Int,
        val supportedPlatforms: Int,
        val activePlatforms: Int
    )
}

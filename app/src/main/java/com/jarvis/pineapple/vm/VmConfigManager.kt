package com.jarvis.pineapple.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 虚拟机配置
 */
data class VmConfig(
    val vmId: String,
    val name: String,
    val cpuCores: Int = 2,
    val memoryMb: Int = 2048,
    val diskGb: Int = 20,
    val networkMode: NetworkMode = NetworkMode.NAT,
    val enableGpu: Boolean = false,
    val enableSharedFolder: Boolean = false,
    val sharedFolderPath: String = "",
    val bootOrder: List<String> = listOf("disk", "cdrom", "network"),
    val kernelParams: String = "",
    val environmentVariables: Map<String, String> = emptyMap()
)

/**
 * 配置验证结果
 */
sealed class ConfigValidationResult {
    data class Valid(val message: String = "配置有效") : ConfigValidationResult()
    data class Invalid(val errors: List<String>) : ConfigValidationResult()
    data class Warning(val warnings: List<String>) : ConfigValidationResult()
}

/**
 * 虚拟机配置管理器（REQ-01-S4）。
 */
class VmConfigManager(
    private val vmId: String
) {
    private val _config = MutableStateFlow(
        VmConfig(
            vmId = vmId,
            name = "VM-$vmId"
        )
    )
    val config: StateFlow<VmConfig> = _config.asStateFlow()

    private val _isModified = MutableStateFlow(false)
    val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private var originalConfig = _config.value

    fun getConfig(): VmConfig {
        return _config.value
    }

    fun setCpuCores(cores: Int) {
        _config.value = _config.value.copy(cpuCores = cores.coerceIn(1, 16))
        checkModified()
    }

    fun setMemory(mb: Int) {
        _config.value = _config.value.copy(memoryMb = mb.coerceIn(256, 32768))
        checkModified()
    }

    fun setDisk(gb: Int) {
        _config.value = _config.value.copy(diskGb = gb.coerceIn(5, 500))
        checkModified()
    }

    fun setNetworkMode(mode: NetworkMode) {
        _config.value = _config.value.copy(networkMode = mode)
        checkModified()
    }

    fun setGpuEnabled(enabled: Boolean) {
        _config.value = _config.value.copy(enableGpu = enabled)
        checkModified()
    }

    fun setSharedFolderEnabled(enabled: Boolean, path: String = "") {
        _config.value = _config.value.copy(
            enableSharedFolder = enabled,
            sharedFolderPath = if (enabled) path else ""
        )
        checkModified()
    }

    fun setBootOrder(order: List<String>) {
        _config.value = _config.value.copy(bootOrder = order)
        checkModified()
    }

    fun setKernelParams(params: String) {
        _config.value = _config.value.copy(kernelParams = params)
        checkModified()
    }

    fun addEnvironmentVariable(key: String, value: String) {
        val env = _config.value.environmentVariables.toMutableMap()
        env[key] = value
        _config.value = _config.value.copy(environmentVariables = env)
        checkModified()
    }

    fun removeEnvironmentVariable(key: String) {
        val env = _config.value.environmentVariables.toMutableMap()
        env.remove(key)
        _config.value = _config.value.copy(environmentVariables = env)
        checkModified()
    }

    fun rename(name: String) {
        _config.value = _config.value.copy(name = name)
        checkModified()
    }

    fun validate(): ConfigValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val config = _config.value

        if (config.cpuCores < 1) {
            errors.add("CPU 核数不能小于 1")
        }
        if (config.memoryMb < 256) {
            errors.add("内存不能小于 256MB")
        }
        if (config.diskGb < 5) {
            errors.add("磁盘不能小于 5GB")
        }
        if (config.enableSharedFolder && config.sharedFolderPath.isEmpty()) {
            errors.add("启用共享文件夹时必须指定路径")
        }
        if (config.cpuCores > 8) {
            warnings.add("CPU 核数超过 8 可能导致性能问题")
        }
        if (config.memoryMb > 16384) {
            warnings.add("内存超过 16GB 可能导致主机内存不足")
        }

        return when {
            errors.isNotEmpty() -> ConfigValidationResult.Invalid(errors)
            warnings.isNotEmpty() -> ConfigValidationResult.Warning(warnings)
            else -> ConfigValidationResult.Valid()
        }
    }

    fun applyConfig(): Boolean {
        val validation = validate()
        return when (validation) {
            is ConfigValidationResult.Valid -> {
                originalConfig = _config.value
                _isModified.value = false
                true
            }
            is ConfigValidationResult.Warning -> {
                originalConfig = _config.value
                _isModified.value = false
                true
            }
            is ConfigValidationResult.Invalid -> false
        }
    }

    fun resetToOriginal() {
        _config.value = originalConfig
        _isModified.value = false
    }

    fun resetToDefault() {
        _config.value = VmConfig(vmId = vmId, name = "VM-$vmId")
        originalConfig = _config.value
        _isModified.value = false
    }

    fun getConfigSummary(): String {
        val config = _config.value
        return buildString {
            appendLine("=== 虚拟机配置 ===")
            appendLine("名称: ${config.name}")
            appendLine("CPU: ${config.cpuCores} 核")
            appendLine("内存: ${config.memoryMb} MB")
            appendLine("磁盘: ${config.diskGb} GB")
            appendLine("网络: ${config.networkMode}")
            appendLine("GPU: ${if (config.enableGpu) "启用" else "禁用"}")
            appendLine("共享文件夹: ${if (config.enableSharedFolder) "启用 (${config.sharedFolderPath})" else "禁用"}")
            appendLine("开机顺序: ${config.bootOrder.joinToString(" -> ")}")
            if (config.kernelParams.isNotEmpty()) {
                appendLine("内核参数: ${config.kernelParams}")
            }
            if (config.environmentVariables.isNotEmpty()) {
                appendLine("环境变量:")
                config.environmentVariables.forEach { (k, v) ->
                    appendLine("  $k=$v")
                }
            }
        }
    }

    private fun checkModified() {
        _isModified.value = _config.value != originalConfig
    }
}

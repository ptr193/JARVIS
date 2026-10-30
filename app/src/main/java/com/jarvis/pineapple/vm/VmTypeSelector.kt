package com.jarvis.pineapple.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 虚拟机类型选择器。
 *
 * 支持两种虚拟机类型：
 * 1. 传统虚拟机（终端式）- 基于 proot 的轻量级 Linux 环境
 * 2. Haven 风格虚拟机（桌面式）- 基于 PRoot + Wayland 的完整桌面体验
 */
class VmTypeSelector {

    /**
     * 虚拟机类型
     */
    enum class VmType {
        TRADITIONAL,    // 传统终端式虚拟机
        HAVEN           // Haven 风格桌面式虚拟机
    }

    /**
     * 虚拟机类型信息
     */
    data class VmTypeInfo(
        val type: VmType,
        val name: String,
        val description: String,
        val features: List<String>,
        val requirements: String,
        val isAvailable: Boolean
    )

    private val _selectedType = MutableStateFlow(VmType.TRADITIONAL)
    val selectedType: StateFlow<VmType> = _selectedType.asStateFlow()

    /**
     * 获取所有虚拟机类型信息
     */
    fun getAvailableTypes(): List<VmTypeInfo> {
        return listOf(
            VmTypeInfo(
                type = VmType.TRADITIONAL,
                name = "传统虚拟机",
                description = "轻量级终端式 Linux 环境，适合命令行操作和开发",
                features = listOf(
                    "轻量级，启动快速",
                    "终端命令行操作",
                    "支持软件包管理",
                    "文件管理",
                    "低功耗"
                ),
                requirements = "Android 6.0+, 512MB RAM",
                isAvailable = true
            ),
            VmTypeInfo(
                type = VmType.HAVEN,
                name = "桌面虚拟机",
                description = "完整的 Linux 桌面体验，带图形界面",
                features = listOf(
                    "Wayland 图形桌面",
                    "GPU 加速显示",
                    "完整的 Linux 桌面环境",
                    "支持 GUI 应用",
                    "多窗口管理",
                    "远程桌面访问"
                ),
                requirements = "Android 8.0+, 2GB RAM, 支持 GPU",
                isAvailable = true
            )
        )
    }

    /**
     * 选择虚拟机类型
     */
    fun selectType(type: VmType) {
        _selectedType.value = type
    }

    /**
     * 获取当前选中的类型
     */
    fun getSelectedType(): VmType = _selectedType.value

    /**
     * 获取指定类型的信息
     */
    fun getTypeInfo(type: VmType): VmTypeInfo? {
        return getAvailableTypes().find { it.type == type }
    }
}

package com.jarvis.pineapple.ui.settings

/**
 * 设置页标签规格（产品规格书 3.3 十标签 + 已接入的扩展入口）。
 */
data class SettingsTabSpec(
    val title: String,
    val description: String
)

object SettingsTabs {
    const val PREFS = "jarvis_settings"
    const val KEY_INDEX = "selected_tab"

    val SPEC_TABS: List<SettingsTabSpec> = listOf(
        SettingsTabSpec("代理", "设置灵魂（人格描述）"),
        SettingsTabSpec("服务", "AI、API 接入与服务器连接"),
        SettingsTabSpec("工具", "技能导入与卸载"),
        SettingsTabSpec("虚拟机", "虚拟机参数与性能限制"),
        SettingsTabSpec("状态", "CPU、内存、温度与电池实时信息"),
        SettingsTabSpec("仓库", "GitHub / GitLab 仓库管理"),
        SettingsTabSpec("连接设备", "已连接与可发现的手机、电脑、家居设备"),
        SettingsTabSpec("语言语音", "AI 声线与语言（默认钢铁侠中文男声）"),
        SettingsTabSpec("日志", "日志文件查看与导出"),
        SettingsTabSpec("通用", "背景图、主题色、唤醒方式与默认助手")
    )

    val EXTRA_TABS: List<SettingsTabSpec> = listOf(
        SettingsTabSpec("智能中枢", "感知、家居、健康与自主决策入口"),
        SettingsTabSpec("安全中心", "权限、生物识别与设备管理员"),
        SettingsTabSpec("系统操控", "电话、短信、日程与导航")
    )

    val ALL_TABS: List<SettingsTabSpec> = SPEC_TABS + EXTRA_TABS

    fun clampIndex(index: Int, size: Int = ALL_TABS.size): Int {
        if (size <= 0) return 0
        return index.coerceIn(0, size - 1)
    }
}

package com.jarvis.pineapple.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTabsTest {

    @Test
    fun specTabsMatchProductSpecSection33() {
        val titles = SettingsTabs.SPEC_TABS.map { it.title }
        assertEquals(
            listOf("代理", "服务", "工具", "虚拟机", "状态", "仓库", "连接设备", "语言语音", "日志", "通用"),
            titles
        )
        SettingsTabs.SPEC_TABS.forEach { tab ->
            assertTrue("${tab.title} 缺少说明", tab.description.isNotBlank())
        }
        assertEquals("设置灵魂（人格描述）", SettingsTabs.SPEC_TABS[0].description)
        assertEquals("CPU、内存、温度与电池实时信息", SettingsTabs.SPEC_TABS[4].description)
        assertEquals("背景图、主题色、唤醒方式与默认助手", SettingsTabs.SPEC_TABS[9].description)
    }

    @Test
    fun clampIndexPersistsWithinBounds() {
        assertEquals(0, SettingsTabs.clampIndex(-3))
        assertEquals(0, SettingsTabs.clampIndex(0))
        assertEquals(9, SettingsTabs.clampIndex(9))
        assertEquals(SettingsTabs.ALL_TABS.lastIndex, SettingsTabs.clampIndex(99))
        assertEquals(0, SettingsTabs.clampIndex(2, size = 0))
    }
}

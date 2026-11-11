package com.jarvis.pineapple.ui.theme

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EyeCareThemeTest {

    @Test
    fun presetsDropNeonGreenAndKeepWarmAccents() {
        val names = UiCustomizationRepository.PRESET_COLORS.map { it.first }
        val values = UiCustomizationRepository.PRESET_COLORS.map { it.second }
        assertFalse(names.contains("翠绿"))
        assertFalse(values.contains(0xFF39FF14L))
        assertTrue(names.contains("暖琥珀"))
        assertTrue(names.contains("纸质浅棕"))
        assertTrue(values.contains(0xFFC9A227L))
        assertTrue(values.contains(0xFFA89070L))
    }

    @Test
    fun themeAndOverlaySourcesDropNeonGreen() {
        val theme = readSource("app/src/main/java/com/jarvis/pineapple/ui/theme/Theme.kt")
        assertFalse(theme.contains("39FF14"))
        assertTrue(theme.contains("0xFF1C1916"))
        assertTrue(theme.contains("0xFFF6F0E6"))
        val overlay = readSource("app/src/main/res/layout/voice_assist_overlay.xml")
        assertTrue(overlay.contains("#E61C1916"))
        val camera = readSource("app/src/main/res/layout/overlay_camera.xml")
        assertTrue(camera.contains("#FF1C1916"))
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(File(relativePath), File("../$relativePath"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("无法定位源文件: $relativePath")
        return file.readText()
    }
}

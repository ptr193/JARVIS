package com.jarvis.pineapple.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SecondaryDisplayManager 纯逻辑单测。
 * 注意：DisplayManager.getDisplays 依赖真实 Android 运行时，JVM 单测中框架存根不可用，
 * 因此只覆盖可脱离框架的纯判断逻辑 summarize / summarizeDisplays。
 */
class SecondaryDisplayManagerTest {

    @Test
    fun summarizeEmptyReportsNoDisplay() {
        val text = SecondaryDisplayManager.summarizeDisplays(emptyList())
        assertTrue(text.contains("未检测到外接显示"))
    }

    @Test
    fun summarizeListsDisplayResolution() {
        val displays = listOf(
            SecondaryDisplayManager.DisplayInfo(1, "HDMI-1", 1920, 1080)
        )
        val text = SecondaryDisplayManager.summarizeDisplays(displays)
        assertTrue(text.contains("检测到 1 个外接显示"))
        assertTrue(text.contains("HDMI-1"))
        assertTrue(text.contains("1920×1080"))
    }

    @Test
    fun summarizeErrorReportsFailureInsteadOfEmpty() {
        val text = SecondaryDisplayManager.summarize(
            SecondaryDisplayManager.ProbeResult.Error("服务不可用")
        )
        assertTrue(text.contains("探测失败"))
        assertTrue(text.contains("服务不可用"))
        // 失败不得被伪装成“未检测到设备”
        assertFalse(text.contains("未检测到外接显示"))
    }

    @Test
    fun summarizeOkWithEmptyDoesNotClaimFailure() {
        val text = SecondaryDisplayManager.summarize(
            SecondaryDisplayManager.ProbeResult.Ok(emptyList())
        )
        assertFalse(text.contains("探测失败"))
        assertTrue(text.contains("未检测到外接显示"))
    }
}

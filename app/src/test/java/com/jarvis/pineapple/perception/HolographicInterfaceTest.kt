package com.jarvis.pineapple.perception

import com.google.ar.core.ArCoreApk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HolographicInterface 纯逻辑单测。
 *
 * 注意：真实 ARCore 可用性探测（ArCoreApk.checkAvailability）依赖 Android 运行时，
 * JVM 单测中框架存根不可用，因此只覆盖可脱离框架的纯判断逻辑：
 * describeArAvailability 与 summarize，确保对“不支持”不会伪装成“可用”。
 */
class HolographicInterfaceTest {

    @Test
    fun describeArInstalledMarksAvailable() {
        val text = HolographicInterface.describeArAvailability(
            ArCoreApk.Availability.SUPPORTED_INSTALLED
        )
        assertTrue(text.contains("可启动 AR"))
    }

    @Test
    fun describeArNotInstalledIsHonest() {
        val text = HolographicInterface.describeArAvailability(
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED
        )
        assertTrue(text.contains("未安装 ARCore"))
        assertFalse(text.contains("可启动 AR"))
    }

    @Test
    fun describeArUnsupportedDeviceIsHonest() {
        val text = HolographicInterface.describeArAvailability(
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE
        )
        assertTrue(text.contains("不支持"))
    }

    @Test
    fun summarizeCoversAllFourBoundariesAndCountsAvailable() {
        val report = HolographicInterface.CapabilityReport(
            holographicProjection = HolographicInterface.Capability(false, "无硬件"),
            ar = HolographicInterface.Capability(true, "已安装"),
            roboticArm = HolographicInterface.Capability(false, "无机械臂"),
            gesture = HolographicInterface.Capability(false, "无模型")
        )
        val text = HolographicInterface.summarize(report)
        assertTrue(text.contains("#114"))
        assertTrue(text.contains("#115"))
        assertTrue(text.contains("#116"))
        assertTrue(text.contains("#97"))
        assertEquals(1, report.availableCount)
    }

    @Test
    fun unavailableCapabilityIsNeverMarkedAvailable() {
        val c = HolographicInterface.Capability(false, "无硬件")
        assertFalse(c.available)
        assertTrue(c.detail.isNotBlank())
    }
}

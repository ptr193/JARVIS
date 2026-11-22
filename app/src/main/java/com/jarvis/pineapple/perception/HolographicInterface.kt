package com.jarvis.pineapple.perception

import android.content.Context
import com.google.ar.core.ArCoreApk

/**
 * 全息 / AR / 机械臂 / 手势能力边界（#97 / #114 / #115 / #116）。
 *
 * 这些能力依赖消费级设备通常不具备的硬件或 SDK，本类只做真实/诚实的能力探测，
 * 绝不把“无硬件/未集成”伪装成可用：
 * - #114 全息投影：需专用全息投影硬件 → 不可用
 * - #115 AR 界面：接入 ARCore，真实探测设备是否支持、是否安装 ARCore；实际渲染需支持设备
 * - #116 精准抓取：需外接机械臂 → 不可用
 * - #97 手势识别：当前未集成摄像头手部追踪模型 → 不可用
 */
class HolographicInterface(private val context: Context) {

    data class Capability(val available: Boolean, val detail: String)

    data class CapabilityReport(
        val holographicProjection: Capability,
        val ar: Capability,
        val roboticArm: Capability,
        val gesture: Capability
    ) {
        val availableCount: Int get() = listOf(
            holographicProjection, ar, roboticArm, gesture
        ).count { it.available }
    }

    /** 全息投影（#114）：消费级设备无此硬件。 */
    fun holographicProjectionCapability(): Capability =
        Capability(false, "无全息投影硬件（需专用投影设备）")

    /** 机械臂精准抓取（#116）：未检测到外接机械臂。 */
    fun roboticArmCapability(): Capability =
        Capability(false, "未检测到外接机械臂")

    /** 手势识别（#97）：当前未集成摄像头手部追踪模型。 */
    fun gestureCapability(): Capability =
        Capability(false, "未集成摄像头手部追踪模型，无法识别手势")

    /**
     * AR 界面（#115）：真实调用 ARCore 探测可用性。
     * 仅当设备支持且已安装 ARCore 应用时才报告可用，绝不伪造。
     */
    fun arCapability(): Capability {
        val availability = try {
            ArCoreApk.getInstance().checkAvailability(context)
        } catch (e: Exception) {
            return Capability(false, "ARCore 探测异常：${e.message ?: e.javaClass.simpleName}")
        }
        return Capability(
            available = availability == ArCoreApk.Availability.SUPPORTED_INSTALLED,
            detail = describeArAvailability(availability)
        )
    }

    /** 一次性探测全部边界能力。 */
    fun probeAll(): CapabilityReport = CapabilityReport(
        holographicProjection = holographicProjectionCapability(),
        ar = arCapability(),
        roboticArm = roboticArmCapability(),
        gesture = gestureCapability()
    )

    companion object {
        /** 纯逻辑：把 ARCore 可用性映射为可读说明，便于脱离 Context 单测。 */
        fun describeArAvailability(availability: ArCoreApk.Availability): String =
            when (availability) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED ->
                    "设备支持 ARCore 且已安装，可启动 AR"
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ->
                    "设备支持 AR，但 ARCore 应用版本过旧，需更新"
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ->
                    "设备支持 AR，但未安装 ARCore 应用"
                ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                    "设备不支持 ARCore"
                ArCoreApk.Availability.UNKNOWN_CHECKING ->
                    "AR 可用性仍在探测中，请重试"
                ArCoreApk.Availability.UNKNOWN_ERROR ->
                    "AR 可用性探测失败"
                ArCoreApk.Availability.UNKNOWN_TIMED_OUT ->
                    "AR 可用性探测超时"
                else ->
                    "设备不支持 AR（未知原因：$availability）"
            }

        /** 纯逻辑：把能力报告格式化为摘要文本，便于单测。 */
        fun summarize(report: CapabilityReport): String = buildString {
            appendLine("全息投影 (#114)：${label(report.holographicProjection)}")
            appendLine("AR 界面 (#115)：${label(report.ar)}")
            appendLine("精准抓取 (#116)：${label(report.roboticArm)}")
            append("手势识别 (#97)：${label(report.gesture)}")
        }

        private fun label(c: Capability): String =
            if (c.available) "可用 — ${c.detail}" else "不可用 — ${c.detail}"
    }
}

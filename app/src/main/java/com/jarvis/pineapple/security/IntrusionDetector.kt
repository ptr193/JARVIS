package com.jarvis.pineapple.security

import android.content.Context
import android.provider.Settings

enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }

data class IntrusionFinding(
    val type: String,
    val severity: Severity,
    val description: String
)

data class IntrusionReport(
    val findings: List<IntrusionFinding>,
    val isCompromised: Boolean,
    val summary: String
)

data class IntrusionFlags(
    val adbEnabled: Boolean,
    val developmentSettingsEnabled: Boolean,
    val unknownSourcesAllowed: Boolean,
    val mockLocationEnabled: Boolean
)

/**
 * 反入侵检测（#46）。
 *
 * 基于真实系统设置读取可能的入侵/风险迹象：ADB 开启、开发者选项、
 * 允许未知来源安装、允许模拟位置。读取失败时不伪造结果，返回空报告。
 */
class IntrusionDetector(private val context: Context) {

    fun detectIntrusion(): IntrusionReport = evaluate(readFlags())

    private fun readFlags(): IntrusionFlags = try {
        val cr = context.contentResolver
        IntrusionFlags(
            adbEnabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1,
            developmentSettingsEnabled = Settings.Global.getInt(
                cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            ) == 1,
            unknownSourcesAllowed = Settings.Global.getInt(
                cr, Settings.Global.INSTALL_NON_MARKET_APPS, 0
            ) == 1,
            mockLocationEnabled = Settings.Secure.getInt(
                cr, Settings.Secure.ALLOW_MOCK_LOCATION, 0
            ) == 1
        )
    } catch (_: Exception) {
        IntrusionFlags(false, false, false, false)
    }

    companion object {
        /** 纯逻辑：根据系统标志生成入侵报告，便于脱离 Context 单测。 */
        fun evaluate(flags: IntrusionFlags): IntrusionReport {
            val findings = mutableListOf<IntrusionFinding>()
            if (flags.adbEnabled) {
                findings += IntrusionFinding("ADB", Severity.HIGH, "ADB 调试已开启，存在被物理/网络调试入侵风险")
            }
            if (flags.developmentSettingsEnabled) {
                findings += IntrusionFinding("开发者选项", Severity.MEDIUM, "开发者选项已开启")
            }
            if (flags.unknownSourcesAllowed) {
                findings += IntrusionFinding("未知来源安装", Severity.HIGH, "允许安装未知来源应用")
            }
            if (flags.mockLocationEnabled) {
                findings += IntrusionFinding("模拟位置", Severity.MEDIUM, "允许模拟位置，可能被用于位置欺诈")
            }
            val isCompromised = findings.any { it.severity == Severity.HIGH || it.severity == Severity.CRITICAL }
            val summary = when {
                findings.isEmpty() -> "未发现入侵风险迹象"
                isCompromised -> "检测到 ${findings.size} 项风险（含高危），建议立即响应"
                else -> "检测到 ${findings.size} 项风险，建议关注"
            }
            return IntrusionReport(findings.sortedByDescending { it.severity }, isCompromised, summary)
        }
    }
}

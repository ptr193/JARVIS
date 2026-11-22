package com.jarvis.pineapple.system

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * 外接显示探测（#72）。
 *
 * 基于真实 [DisplayManager] 列出可用于演示的外接显示（DISPLAY_CATEGORY_PRESENTATION，
 * 含 HDMI/无线投屏等外接屏，不含设备主屏）。
 *
 * 边界：能否真正输出内容取决于外接硬件与系统投屏开关；这里只做真实探测，
 * 区分「未检测到设备」与「探测失败」，绝不把失败伪装成无设备。
 */
class SecondaryDisplayManager(private val context: Context) {

    data class DisplayInfo(
        val id: Int,
        val name: String,
        val width: Int,
        val height: Int
    )

    sealed class ProbeResult {
        data class Ok(val displays: List<DisplayInfo>) : ProbeResult()
        data class Error(val reason: String) : ProbeResult()
    }

    /** 探测当前外接显示；无服务或异常时明确报告失败，而非伪装成空列表。 */
    fun probeDisplays(): ProbeResult = try {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return ProbeResult.Error("无法获取 DisplayManager 服务")
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .map { d ->
                val mode = d.mode
                DisplayInfo(
                    id = d.displayId,
                    name = d.name?.takeIf { it.isNotBlank() } ?: "Display ${d.displayId}",
                    width = mode?.physicalWidth ?: 0,
                    height = mode?.physicalHeight ?: 0
                )
            }
            .sortedBy { it.id }
        ProbeResult.Ok(displays)
    } catch (e: Exception) {
        ProbeResult.Error(e.message ?: e.javaClass.simpleName)
    }

    companion object {
        /** 纯逻辑：把探测结果格式化为可读摘要，便于单测。 */
        fun summarize(result: ProbeResult): String = when (result) {
            is ProbeResult.Error -> "外接显示探测失败：${result.reason}"
            is ProbeResult.Ok -> summarizeDisplays(result.displays)
        }

        fun summarizeDisplays(displays: List<DisplayInfo>): String =
            if (displays.isEmpty()) {
                "未检测到外接显示设备（需连接外接屏或开启无线显示）"
            } else {
                "检测到 ${displays.size} 个外接显示：\n" +
                    displays.joinToString("\n") {
                        "· ${it.name} (${it.width}×${it.height}, id=${it.id})"
                    }
            }
    }
}

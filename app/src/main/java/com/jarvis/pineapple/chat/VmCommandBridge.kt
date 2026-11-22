package com.jarvis.pineapple.chat

/**
 * 供对话引擎操控虚拟机的抽象桥接。
 *
 * 将对话层与具体 PRoot 实现解耦，便于在 JVM 单测中验证解析与调度逻辑。
 */
interface VmCommandBridge {
    /** 当前已登记的虚拟机概要。 */
    suspend fun listVms(): List<VmSummary>

    /** 启动指定虚拟机；返回是否真实启动成功。 */
    suspend fun start(vmId: String): Boolean

    /** 在指定虚拟机内执行命令；返回真实输出与退出码。 */
    suspend fun run(vmId: String, command: String): VmRunResult

    /** 启动并连接虚拟机图形桌面（VNC）；返回真实结果。 */
    suspend fun openDesktop(vmId: String): VmDesktopActionResult

    /** 在虚拟机桌面中打开浏览器；桌面未启动时先启动桌面。 */
    suspend fun openBrowser(vmId: String): VmDesktopActionResult
}

data class VmDesktopActionResult(
    val ok: Boolean,
    val message: String
)

data class VmSummary(
    val id: String,
    val name: String,
    val running: Boolean
)

data class VmRunResult(
    val output: String,
    val exitCode: Int
)

/**
 * 解析“AI 操控虚拟机”类指令（纯函数，可单测）。
 *
 * 支持格式：
 * - `虚拟机 Ubuntu 执行 ls -la`
 * - `在虚拟机 Ubuntu 执行 ls -la`
 * - `虚拟机执行 whoami`（未指定名称时选用运行中的/第一个虚拟机）
 * - `终端执行 uname -a`
 * - `vm: uname -a`
 *
 * 未命中返回 null，避免劫持普通对话。
 */
object VmCommandParser {

    private val VM_PATTERN = Regex(
        "^(?:在)?虚拟机\\s*(.*?)\\s*执行\\s*[:：]?\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val TERMINAL_PATTERN = Regex(
        "^终端执行\\s*[:：]?\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val COLON_PATTERN = Regex("^vm\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)

    fun parse(message: String): VmExecution? {
        val text = message.trim()
        if (text.isEmpty() || text.length > 500) return null

        vmPatternMatch(text)?.let { return it }
        TERMINAL_PATTERN.find(text)?.let { match ->
            val command = match.groupValues[1].trim()
            if (command.isNotEmpty()) return VmExecution(null, command)
        }
        COLON_PATTERN.find(text)?.let { match ->
            val command = match.groupValues[1].trim()
            if (command.isNotEmpty()) return VmExecution(null, command)
        }
        return null
    }

    private fun vmPatternMatch(text: String): VmExecution? {
        val match = VM_PATTERN.find(text) ?: return null
        val name = match.groupValues[1].trim().ifBlank { null }
        val command = match.groupValues[2].trim()
        if (command.isEmpty()) return null
        // 形如“虚拟机执行 ...”时，正则的惰性分组可能吞入“执行”前的空格，已 trim 处理。
        return VmExecution(name, command)
    }
}

data class VmExecution(
    /** 目标虚拟机名称；为 null 表示自动选择。 */
    val vmName: String?,
    val command: String
)

enum class VmDesktopAction { DESKTOP, BROWSER }

data class VmDesktopRequest(
    val action: VmDesktopAction,
    /** 目标虚拟机名称；为 null 表示自动选择。 */
    val vmName: String?
)

/**
 * 解析“打开虚拟机桌面 / 在虚拟机中使用浏览器”类指令（纯函数，可单测）。
 * 与 [VmCommandParser] 互斥：显式“执行命令”的请求仍由后者处理。
 */
object VmDesktopCommandParser {

    private val BROWSER = Regex(
        "(?:打开|启动|使用|调用).{0,10}虚拟机.{0,10}浏览器|虚拟机.{0,10}浏览器",
        RegexOption.IGNORE_CASE
    )
    private val DESKTOP = Regex(
        "(?:打开|启动|显示|进入).{0,10}虚拟机.{0,10}桌面|虚拟机.{0,10}桌面",
        RegexOption.IGNORE_CASE
    )
    private val NAME = Regex(
        "虚拟机\\s*[\\\"“]?([A-Za-z0-9_\\-\\u4e00-\\u9fa5]+)[\\\"”]?\\s*(?:的)?\\s*(?:打开|启动|进入|显示)?\\s*(?:桌面|浏览器)"
    )

    fun parse(message: String): VmDesktopRequest? {
        val text = message.trim()
        if (text.isEmpty() || text.length > 200) return null
        if (VmCommandParser.parse(text) != null) return null

        val action = when {
            BROWSER.containsMatchIn(text) -> VmDesktopAction.BROWSER
            DESKTOP.containsMatchIn(text) -> VmDesktopAction.DESKTOP
            else -> return null
        }

        val candidate = NAME.find(text)?.groupValues?.get(1)?.trim()?.removeSuffix("的")
        val vmName = candidate?.takeIf {
            it.isNotBlank() && it !in setOf("打开", "启动", "使用", "调用", "桌面", "浏览器")
        }
        return VmDesktopRequest(action, vmName)
    }
}

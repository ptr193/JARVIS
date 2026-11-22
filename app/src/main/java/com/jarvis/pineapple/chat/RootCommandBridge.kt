package com.jarvis.pineapple.chat

/**
 * 对话层访问“宿主机 root”能力的抽象桥接。
 * 真实实现通过 `su -c` 执行；设备未 root、用户未授权或执行失败都会如实返回。
 */
interface RootCommandBridge {
    /** 当前授权与 root 可用状态。 */
    suspend fun status(): RootAccessStatus

    /** 以 root 身份执行宿主机命令，返回真实输出与退出码。 */
    suspend fun run(command: String): RootRunResult
}

data class RootAccessStatus(
    /** 用户是否允许 AI 使用宿主机 root。 */
    val allowed: Boolean,
    /** 设备是否已 root 且 su 授权成功。 */
    val granted: Boolean,
    val detail: String
)

data class RootRunResult(
    val output: String,
    val exitCode: Int
)

/**
 * 解析“以 root 身份在宿主机执行命令”的指令（纯函数，可单测）。
 *
 * 支持：
 * - `root 执行 id`
 * - `以root权限执行 getprop ro.build.version.release`
 * - `宿主机执行 ls /system`
 * - `su: id`
 *
 * 未命中返回 null，避免劫持普通对话。
 */
object RootCommandParser {
    private val PATTERNS = listOf(
        Regex(
            "^(?:以|用)?\\s*root(?:权限|身份)?\\s*(?:在)?(?:宿主机|手机|设备)?\\s*(?:上)?\\s*执行\\s*[:：]?\\s*(.+)$",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "^宿主机\\s*(?:root)?\\s*执行\\s*[:：]?\\s*(.+)$",
            RegexOption.IGNORE_CASE
        ),
        Regex("^su\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
    )

    fun parse(message: String): String? {
        val text = message.trim()
        if (text.isEmpty() || text.length > 500) return null
        for (pattern in PATTERNS) {
            val command = pattern.find(text)?.groupValues?.get(1)?.trim()
            if (!command.isNullOrEmpty()) return command
        }
        return null
    }
}

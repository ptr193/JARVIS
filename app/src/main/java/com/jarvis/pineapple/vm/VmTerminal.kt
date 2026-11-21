package com.jarvis.pineapple.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 终端会话状态
 */
data class TerminalSession(
    val sessionId: String,
    val vmId: String,
    val isConnected: Boolean = false,
    val currentDirectory: String = "/root",
    val history: List<String> = emptyList(),
    val output: List<String> = emptyList()
)

/**
 * 命令执行结果
 */
data class CommandResult(
    val command: String,
    val output: String,
    val exitCode: Int,
    val executionTimeMs: Long
)

/**
 * 虚拟机终端。
 *
 * 通过 [ProotManager] 将命令真实地发送到指定的 rootfs 执行，
 * 并维护会话状态（当前目录、历史、输出）。
 */
class VmTerminal(
    private val vmId: String,
    private val rootfsPath: String,
    private val prootManager: ProotManager,
    private val useRoot: Boolean = true
) {
    private val _session = MutableStateFlow(
        TerminalSession(
            sessionId = "term_${System.currentTimeMillis()}",
            vmId = vmId
        )
    )
    val session: StateFlow<TerminalSession> = _session.asStateFlow()

    private val _output = MutableStateFlow<List<String>>(emptyList())
    val output: StateFlow<List<String>> = _output.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val commandHistory = mutableListOf<String>()
    private var historyIndex = 0

    /** 仅在 rootfs 与 PRoot 均可使用时建立终端会话。 */
    fun connect(): Boolean {
        val ready = java.io.File(rootfsPath).isDirectory && prootManager.isProotInstalled()
        _session.value = _session.value.copy(isConnected = ready)
        return ready
    }

    /** 断开连接。 */
    fun disconnect() {
        _session.value = _session.value.copy(isConnected = false)
    }

    /** 清空输出缓冲区。 */
    fun clearOutput() {
        _output.value = emptyList()
    }

    /**
     * 执行一条命令。
     */
    suspend fun executeCommand(command: String): CommandResult {
        if (!_session.value.isConnected) {
            return CommandResult(command, "Error: 未连接到虚拟机", -1, 0)
        }

        val trimmed = command.trim()
        if (trimmed.isEmpty()) return CommandResult(command, "", 0, 0)

        _isExecuting.value = true
        commandHistory.add(trimmed)
        historyIndex = commandHistory.size
        _session.value = _session.value.copy(history = commandHistory.toList())

        val result: CommandResult = when {
            trimmed == "clear" -> {
                clearOutput()
                CommandResult(command, "", 0, 0)
            }
            trimmed == "exit" || trimmed == "logout" -> {
                disconnect()
                CommandResult(command, "logout", 0, 0)
            }
            trimmed == "help" -> CommandResult(command, HELP_TEXT, 0, 0)
            trimmed == "cd" || trimmed.startsWith("cd ") -> {
                val target = trimmed.removePrefix("cd").trim().ifEmpty { "/root" }
                val newDir = resolveWorkingDirectory(
                    rootfsPath,
                    _session.value.currentDirectory,
                    target
                )
                if (newDir == null) {
                    CommandResult(command, "Error: 目录不存在或超出 rootfs: $target", -1, 0)
                } else {
                    _session.value = _session.value.copy(currentDirectory = newDir)
                    CommandResult(command, "", 0, 0)
                }
            }
            else -> {
                val config = ProotManager.ProotConfig(
                    rootfsPath = rootfsPath,
                    workingDir = _session.value.currentDirectory,
                    asRoot = useRoot
                )
                val r = prootManager.executeCommand(config, trimmed)
                CommandResult(trimmed, r.output, r.exitCode, r.executionTimeMs)
            }
        }

        val prompt = "${_session.value.currentDirectory} $ $command"
        _output.value = _output.value + prompt + result.output.split("\n").filter { it.isNotEmpty() }
        _isExecuting.value = false
        return result
    }

    /** 上一条历史命令。 */
    fun previousCommand(): String? {
        if (commandHistory.isEmpty()) return null
        if (historyIndex > 0) historyIndex--
        return commandHistory.getOrNull(historyIndex)
    }

    /** 下一条历史命令。 */
    fun nextCommand(): String? {
        if (commandHistory.isEmpty()) return null
        if (historyIndex < commandHistory.size - 1) {
            historyIndex++
            return commandHistory[historyIndex]
        }
        historyIndex = commandHistory.size
        return ""
    }

    companion object {
        internal fun resolveWorkingDirectory(
            rootfsPath: String,
            currentDirectory: String,
            target: String
        ): String? {
            return try {
                val root = java.io.File(rootfsPath).canonicalFile
                val current = java.io.File(root, currentDirectory.trimStart('/'))
                val candidate = if (target.startsWith("/")) {
                    java.io.File(root, target.trimStart('/'))
                } else {
                    java.io.File(current, target)
                }.canonicalFile
                val rootPath = root.path.trimEnd(java.io.File.separatorChar)
                val candidatePath = candidate.path
                val insideRoot = candidatePath == rootPath ||
                    candidatePath.startsWith(rootPath + java.io.File.separator)
                if (!insideRoot || !candidate.isDirectory) return null
                val relative = candidatePath.removePrefix(rootPath)
                    .trimStart(java.io.File.separatorChar)
                    .replace(java.io.File.separatorChar, '/')
                if (relative.isEmpty()) "/" else "/$relative"
            } catch (e: Exception) {
                null
            }
        }

        val HELP_TEXT: String = """
            可用命令：
              help        显示帮助
              clear       清空屏幕
              exit        断开会话
              cd <dir>    切换目录
            其它命令将转发到 PRoot 中的 Linux 发行版执行。
        """.trimIndent()
    }
}

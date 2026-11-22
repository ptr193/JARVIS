package com.jarvis.pineapple.vm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 虚拟机特权操作审计（REQ-06）。
 *
 * 记录 AI 在虚拟机内执行的命令、身份（root/普通用户）与退出码；
 * 只记录命令本身，不记录任何凭据。日志持久化于应用私有目录并限制条数。
 */
class VmAuditLog(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    init {
        _entries.value = readAll()
    }

    @Synchronized
    fun record(vmName: String, identity: String, command: String, exitCode: Int) {
        val line = "${System.currentTimeMillis()} vm=$vmName identity=$identity exit=$exitCode cmd=$command"
        runCatching {
            file.appendText(line + "\n")
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                file.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        }
        _entries.value = readAll()
    }

    /** 最近审计记录（最新在后）。 */
    fun recent(limit: Int = 100): List<String> = _entries.value.takeLast(limit)

    private fun readAll(): List<String> = runCatching {
        if (!file.exists()) emptyList() else file.readLines().filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private companion object {
        const val FILE_NAME = "vm_privilege_audit.log"
        const val MAX_LINES = 500
    }
}

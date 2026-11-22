package com.jarvis.pineapple.security

import java.io.File
import java.io.IOException

/** 串行、限长的文本审计存储；裁剪通过同目录临时文件替换完成。 */
internal class AuditLogStore(
    private val file: File,
    private val maxLines: Int = 500
) {
    private var entries: List<String> = emptyList()

    @Volatile
    var lastError: String? = null
        private set

    init {
        require(maxLines > 0)
        reload()
    }

    @Synchronized
    fun snapshot(): List<String> = entries

    @Synchronized
    fun append(line: String): Boolean {
        return try {
            file.appendText(AuditSanitizer.sanitize(line) + "\n")
            val persisted = file.readLines().filter { it.isNotBlank() }
            val updated = persisted.takeLast(maxLines)
            if (persisted.size > maxLines) replaceAtomically(updated)
            entries = updated
            lastError = null
            true
        } catch (e: Exception) {
            lastError = "审计日志写入失败：${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    @Synchronized
    private fun reload() {
        try {
            entries = if (file.exists()) {
                file.readLines().filter { it.isNotBlank() }.takeLast(maxLines)
            } else {
                emptyList()
            }
            lastError = null
        } catch (e: Exception) {
            lastError = "审计日志读取失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun replaceAtomically(lines: List<String>) {
        val parent = file.absoluteFile.parentFile
            ?: throw IOException("审计日志没有父目录")
        val temporary = File(parent, "${file.name}.tmp")
        try {
            temporary.writeText(lines.joinToString("\n", postfix = "\n"))
            if (!temporary.renameTo(file)) {
                throw IOException("无法原子替换审计日志")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}

internal object AuditSanitizer {
    private const val REDACTED = "[REDACTED]"

    private val keyValue = Regex(
        """(?i)\b(password|passwd|pwd|passphrase|api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|token|secret|client[_-]?secret|private[_-]?key|authorization)\b(\s*(?:=|:)\s*)(?:"[^"]*"|'[^']*'|[^\s;]+)"""
    )
    private val longOption = Regex(
        """(?i)(--(?:password|passwd|passphrase|api[-_]key|access[-_]token|refresh[-_]token|token|secret|client[-_]secret)(?:\s+|=))(?:"[^"]*"|'[^']*'|[^\s;]+)"""
    )
    private val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    private val urlPassword = Regex("""(?i)(https?://[^\s/:]+:)([^\s/@]+)(@)""")

    fun sanitize(value: String): String {
        val singleLine = singleLine(value)
        val withoutBearer = bearer.replace(singleLine, "Bearer $REDACTED")
        val withoutKeyValues = keyValue.replace(withoutBearer) {
            it.groupValues[1] + it.groupValues[2] + REDACTED
        }
        val withoutOptions = longOption.replace(withoutKeyValues) {
            it.groupValues[1] + REDACTED
        }
        return urlPassword.replace(withoutOptions) {
            it.groupValues[1] + REDACTED + it.groupValues[3]
        }
    }

    fun singleLine(value: String): String = value.replace(Regex("""\p{Cntrl}"""), " ")
}

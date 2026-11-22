package com.jarvis.pineapple.vm

import android.content.Context
import com.jarvis.pineapple.security.AuditLogStore
import com.jarvis.pineapple.security.AuditSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** 虚拟机特权操作审计（REQ-06），敏感参数脱敏且写失败可见。 */
class VmAuditLog internal constructor(
    private val store: AuditLogStore
) {
    constructor(context: Context) : this(AuditLogStore(File(context.filesDir, FILE_NAME)))
    internal constructor(file: File) : this(AuditLogStore(file))

    private val _entries = MutableStateFlow<List<String>>(store.snapshot())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    val lastError: String? get() = store.lastError

    fun record(vmName: String, identity: String, command: String, exitCode: Int): Boolean {
        val line = "${System.currentTimeMillis()} " +
            "vm=${AuditSanitizer.sanitize(vmName)} " +
            "identity=${AuditSanitizer.sanitize(identity)} " +
            "exit=$exitCode cmd=${AuditSanitizer.sanitize(command)}"
        val saved = store.append(line)
        if (saved) _entries.value = store.snapshot()
        return saved
    }

    /** 最近审计记录（最新在后）。 */
    fun recent(limit: Int = 100): List<String> = _entries.value.takeLast(limit)

    private companion object {
        const val FILE_NAME = "vm_privilege_audit.log"
    }
}

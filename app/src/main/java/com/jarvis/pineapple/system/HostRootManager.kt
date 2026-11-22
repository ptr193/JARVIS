package com.jarvis.pineapple.system

import android.content.Context
import com.jarvis.pineapple.chat.RootAccessStatus
import com.jarvis.pineapple.chat.RootRunResult
import com.jarvis.pineapple.security.AuditLogStore
import com.jarvis.pineapple.security.AuditSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 宿主机 root 能力管理。
 *
 * 设备已 root 且用户授权后才通过 `su -c` 执行；所有结果保留真实退出码。
 * 审计日志串行限长保存，敏感参数脱敏，写失败会附加到命令输出。
 */
class HostRootManager internal constructor(
    private val auditStore: AuditLogStore,
    private val allowedProvider: () -> Boolean,
    private val allowedUpdater: (Boolean) -> Unit,
    private val suRunner: (String, Long) -> RootRunResult
) {
    constructor(context: Context) : this(
        auditStore = AuditLogStore(File(context.filesDir, AUDIT_FILE)),
        allowedProvider = {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ALLOW_AI_ROOT, false)
        },
        allowedUpdater = { allowed ->
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ALLOW_AI_ROOT, allowed).apply()
        },
        suRunner = { command, timeoutMs -> runSu(command, timeoutMs) }
    )

    /** 用户是否允许 AI 使用宿主机 root（默认关闭）。 */
    fun isAllowed(): Boolean = allowedProvider()

    fun setAllowed(allowed: Boolean) = allowedUpdater(allowed)

    val auditError: String? get() = auditStore.lastError

    /** 真实探测 root：只有 `su -c id` 成功且输出包含 uid=0 才视为已授权。 */
    suspend fun check(): RootAccessStatus = withContext(Dispatchers.IO) {
        val result = suRunner("id", 15_000)
        val granted = result.exitCode == 0 && result.output.contains("uid=0")
        val detail = when {
            granted -> "设备已 root，su 授权成功（${result.output.lineSequence().firstOrNull().orEmpty()}）"
            result.output.isNotBlank() -> "未获得 root：${result.output.lineSequence().firstOrNull().orEmpty()}"
            else -> "未检测到可用的 su，或超级用户授权被拒绝（设备可能未 root）"
        }
        RootAccessStatus(allowed = isAllowed(), granted = granted, detail = detail)
    }

    /** 以 root 身份执行命令；审计失败不伪造命令失败，但必须明确警告。 */
    suspend fun execute(command: String): RootRunResult = withContext(Dispatchers.IO) {
        if (!isAllowed()) {
            return@withContext RootRunResult("已禁止 AI 使用宿主机 root，请在系统控制中开启授权", -1)
        }
        val result = suRunner(command, 120_000)
        val line = "${System.currentTimeMillis()} exit=${result.exitCode} " +
            "cmd=${AuditSanitizer.sanitize(command)}"
        if (auditStore.append(line)) {
            result
        } else {
            result.copy(
                output = listOf(
                    result.output,
                    "警告：命令已执行，但${auditStore.lastError ?: "审计日志写入失败"}"
                ).filter { it.isNotBlank() }.joinToString("\n")
            )
        }
    }

    /** 最近 root 审计记录（最新在后）。 */
    fun auditEntries(limit: Int = 50): List<String> = auditStore.snapshot().takeLast(limit)

    private companion object {
        const val PREFS_NAME = "jarvis_root_config"
        const val KEY_ALLOW_AI_ROOT = "allow_ai_root"
        const val AUDIT_FILE = "root_audit.log"

        fun runSu(command: String, timeoutMs: Long): RootRunResult {
            return try {
                val process = ProcessBuilder("su", "-c", command).start()
                val stdout = StringBuilder()
                val stderr = StringBuilder()
                val outThread = Thread {
                    runCatching {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { stdout.appendLine(it) }
                        }
                    }
                }.apply { isDaemon = true; start() }
                val errThread = Thread {
                    runCatching {
                        process.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach { stderr.appendLine(it) }
                        }
                    }
                }.apply { isDaemon = true; start() }

                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                var exitCode = -1
                var finished = false
                while (!finished) {
                    try {
                        exitCode = process.exitValue()
                        finished = true
                    } catch (e: IllegalThreadStateException) {
                        if (System.nanoTime() > deadline) {
                            process.destroy()
                            outThread.join(500)
                            errThread.join(500)
                            return RootRunResult("命令执行超时（${timeoutMs / 1000}s）", 124)
                        }
                        Thread.sleep(50)
                    }
                }
                outThread.join(1_000)
                errThread.join(1_000)
                RootRunResult((stdout.toString() + stderr.toString()).trim(), exitCode)
            } catch (e: Exception) {
                RootRunResult("su 执行失败：${e.message ?: e.javaClass.simpleName}", -1)
            }
        }
    }
}

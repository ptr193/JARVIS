package com.jarvis.pineapple.system

import android.content.Context
import com.jarvis.pineapple.chat.RootAccessStatus
import com.jarvis.pineapple.chat.RootRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 宿主机 root 能力管理。
 *
 * 设计约束：
 * - 设备已 root 时，JARVIS 可在用户授权后通过 `su -c` 获取 root 身份执行命令；
 * - 设备未 root、用户拒绝授权或命令失败时，必须如实返回，不得伪造成功；
 * - 是否允许 AI 使用宿主机 root 由独立开关控制（默认关闭）；
 * - 所有 root 命令写入本地审计日志（不记录任何凭据）。
 */
class HostRootManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val auditFile = File(context.filesDir, AUDIT_FILE)

    /** 用户是否允许 AI 使用宿主机 root（默认关闭）。 */
    fun isAllowed(): Boolean = prefs.getBoolean(KEY_ALLOW_AI_ROOT, false)

    fun setAllowed(allowed: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_AI_ROOT, allowed).apply()
    }

    /**
     * 真实探测 root：执行 `su -c id`，只有输出包含 uid=0 才视为已获得 root。
     */
    suspend fun check(): RootAccessStatus = withContext(Dispatchers.IO) {
        val result = runSu("id", timeoutMs = 15_000)
        val granted = result.exitCode == 0 && result.output.contains("uid=0")
        val detail = when {
            granted -> "设备已 root，su 授权成功（${result.output.lineSequence().firstOrNull().orEmpty()}）"
            result.output.isNotBlank() -> "未获得 root：${result.output.lineSequence().firstOrNull().orEmpty()}"
            else -> "未检测到可用的 su，或超级用户授权被拒绝（设备可能未 root）"
        }
        RootAccessStatus(allowed = isAllowed(), granted = granted, detail = detail)
    }

    /**
     * 以 root 身份执行命令。未开启授权开关时直接拒绝。
     */
    suspend fun execute(command: String): RootRunResult = withContext(Dispatchers.IO) {
        if (!isAllowed()) {
            return@withContext RootRunResult("已禁止 AI 使用宿主机 root，请在系统控制中开启授权", -1)
        }
        val result = runSu(command)
        audit(command, result.exitCode)
        result
    }

    /** 最近 root 审计记录（最新在后）。 */
    fun auditEntries(limit: Int = 50): List<String> =
        runCatching {
            if (!auditFile.exists()) emptyList()
            else auditFile.readLines().filter { it.isNotBlank() }.takeLast(limit)
        }.getOrDefault(emptyList())

    private fun audit(command: String, exitCode: Int) {
        runCatching {
            auditFile.appendText("${System.currentTimeMillis()} exit=$exitCode cmd=$command\n")
            val lines = auditFile.readLines()
            if (lines.size > MAX_AUDIT_LINES) {
                auditFile.writeText(lines.takeLast(MAX_AUDIT_LINES).joinToString("\n") + "\n")
            }
        }
    }

    private fun runSu(command: String, timeoutMs: Long = 120_000): RootRunResult {
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

    private companion object {
        const val PREFS_NAME = "jarvis_root_config"
        const val KEY_ALLOW_AI_ROOT = "allow_ai_root"
        const val AUDIT_FILE = "root_audit.log"
        const val MAX_AUDIT_LINES = 500
    }
}

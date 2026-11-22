package com.jarvis.pineapple.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录器。
 *
 * 目的（对应第三轮实机测试 BUG-2026-1029）：
 * 真机上「短暂显示主界面后闪退、之后再也打不开」，但本环境无法连接真机，
 * 用户也难以抓取 logcat。因此在应用内自行持久化崩溃堆栈，
 * 下次打开时用户可直接从「设置 → 日志」导出，无需 USB 调试。
 *
 * 只记录、不吞掉异常：记录完成后仍交回系统默认处理器，
 * 保证进程被正常终止（不会把崩溃「藏」起来变成更诡异的状态）。
 */
object CrashRecorder {

    private const val TAG = "CrashRecorder"
    private const val DIR_NAME = "crash"
    private const val MAX_FILES = 10

    /**
     * 记录崩溃堆栈到 filesDir/crash/crash_<时间戳>.txt。
     *
     * 返回 true 仅当文件已真实写入并通过长度校验；任何失败都只记日志，不再向外抛异常
     * （避免在未捕获异常处理器中二次抛出，掩盖原始崩溃）。
     * 绝不允许“调用没抛异常”就当作记录成功——必须校验落盘结果。
     */
    fun record(context: Context, thread: Thread, throwable: Throwable): Boolean {
        val dir = File(context.filesDir, DIR_NAME)
        return try {
            val ok = recordToDir(dir, thread, throwable)
            if (!ok) Log.e(TAG, "崩溃记录写入失败或校验未通过，未生成崩溃文件")
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "崩溃记录失败", e)
            false
        }
    }

    /** 纯文件逻辑：在指定目录写入并校验一条崩溃记录，便于脱离 Context 单测。 */
    internal fun recordToDir(dir: File, thread: Thread, throwable: Throwable): Boolean {
        if ((!dir.exists() && !dir.mkdirs()) || !dir.isDirectory) return false
        prune(dir)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(dir, "crash_$stamp.txt")
        return writeVerifiedText(file, buildCrashText(thread, throwable))
    }

    private fun buildCrashText(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("=== JARVIS 崩溃报告 ===")
            pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            pw.println("线程: ${thread.name} (id=${thread.id})")
            pw.println("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            pw.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            pw.println("ABI: ${(Build.SUPPORTED_ABIS ?: emptyArray()).joinToString()}")
            pw.println("ROM: ${Build.DISPLAY}")
            pw.println("----- 堆栈 -----")
            throwable.printStackTrace(pw)
        }
        return sw.toString()
    }

    /** 列出已记录的崩溃文件（最新在前）；目录不可读时明确失败。 */
    fun list(context: Context): List<File> {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles() ?: throw IOException("无法读取崩溃记录目录")
        return files
            .filter { it.isFile && it.name.startsWith("crash_") }
            .sortedByDescending { it.name }
    }

    fun hasRecords(context: Context): Boolean = list(context).isNotEmpty()

    /** 读取全部崩溃记录合并为文本；任何文件不可读都使导出失败。 */
    fun readAll(context: Context): String = readRecords(list(context))

    /** 导出崩溃记录到 filesDir；无记录返回 null，写入或校验失败抛出异常。 */
    fun export(context: Context): File? {
        val records = list(context)
        if (records.isEmpty()) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val output = File(context.filesDir, "crash_export_$stamp.txt")
        return exportToFile(records, output)
    }

    internal fun exportToFile(records: List<File>, output: File): File {
        val body = readRecords(records)
        if (!writeVerifiedText(output, body)) {
            throw IOException("崩溃记录导出文件写入失败: ${output.absolutePath}")
        }
        val written = output.readText()
        if (!output.exists() || output.length() == 0L || written != body) {
            output.delete()
            throw IOException("崩溃记录导出文件校验失败: ${output.absolutePath}")
        }
        return output
    }

    internal fun readRecords(files: List<File>): String {
        if (files.isEmpty()) return "（无崩溃记录）"
        return files.joinToString("\n\n") { file ->
            "########## ${file.name} ##########\n${file.readText()}"
        }
    }

    /** 清空崩溃记录并返回删除数量；部分删除失败或仍有残留时不得报告成功。 */
    fun clear(context: Context): Int {
        val deleted = clearRecords(list(context))
        if (list(context).isNotEmpty()) {
            throw IOException("崩溃记录未完全清空")
        }
        return deleted
    }

    internal fun clearRecords(files: List<File>): Int {
        val failed = files.filter { it.exists() && !it.delete() }
        if (failed.isNotEmpty()) {
            throw IOException("${failed.size} 个崩溃文件删除失败: ${failed.joinToString { it.name }}")
        }
        return files.size
    }

    /** 保留最近 MAX_FILES 个记录，避免无限占用存储。 */
    private fun prune(dir: File) {
        val files = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith("crash_") }
            .sortedByDescending { it.name }
        files.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    internal fun writeVerifiedText(file: File, text: String): Boolean = try {
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) return false
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        file.writeText(text, Charsets.UTF_8)
        file.exists() && file.length() == bytes.size.toLong()
    } catch (_: IOException) {
        false
    }
}

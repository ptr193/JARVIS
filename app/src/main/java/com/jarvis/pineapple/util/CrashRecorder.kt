package com.jarvis.pineapple.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
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

    /** 记录崩溃堆栈到 filesDir/crash/crash_<时间戳>.txt。任何失败都只记日志，不再抛异常。 */
    fun record(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val dir = File(context.filesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            prune(dir)

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(dir, "crash_$stamp.txt")

            val sw = StringWriter()
            PrintWriter(sw).use { pw ->
                pw.println("=== JARVIS 崩溃报告 ===")
                pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                pw.println("线程: ${thread.name} (id=${thread.id})")
                pw.println("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
                pw.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                pw.println("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
                pw.println("ROM: ${Build.DISPLAY}")
                pw.println("----- 堆栈 -----")
                throwable.printStackTrace(pw)
            }
            file.writeText(sw.toString())
        } catch (e: Throwable) {
            Log.e(TAG, "崩溃记录失败", e)
        }
    }

    /** 列出已记录的崩溃文件（最新在前）。 */
    fun list(context: Context): List<File> {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith("crash_") }
            .sortedByDescending { it.name }
    }

    /** 读取全部崩溃记录合并为文本，供导出。 */
    fun readAll(context: Context): String {
        val files = list(context)
        if (files.isEmpty()) return "（无崩溃记录）"
        return files.joinToString("\n\n") { f ->
            "########## ${f.name} ##########\n" + runCatching { f.readText() }.getOrDefault("（读取失败）")
        }
    }

    /** 保留最近 MAX_FILES 个记录，避免无限占用存储。 */
    private fun prune(dir: File) {
        val files = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith("crash_") }
            .sortedByDescending { it.name }
        files.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }
}

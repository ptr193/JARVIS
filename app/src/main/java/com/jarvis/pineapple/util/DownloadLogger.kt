package com.jarvis.pineapple.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载目录日志记录器。
 *
 * 真机测试场景：用户无法通过 adb 抓取 logcat，只能提供现象。
 * 本类同时维护：
 * - `filesDir/logs/`：应用内真实日志，供设置页导出与清空；
 * - `Download/JARVIS/`：用户可直接在文件管理器找到的外部副本（尽力而为，失败不影响应用内记录）。
 *
 * 所有“成功”都基于文件真实写入并校验，不允许仅捕获异常后静默返回。
 */
object DownloadLogger {

    private const val TAG = "DownloadLogger"
    private const val EXTERNAL_FOLDER_NAME = "JARVIS"
    private const val APP_LOG_DIR_NAME = "logs"

    private val runtimeLogFileNames = setOf(
        Regex("^jarvis_app_\\d{8}\\.log$"),
        Regex("^jarvis_startup_\\d{8}_\\d{6}\\.txt$"),
        Regex("^data_log\\.jsonl$")
    )

    fun appLogDir(context: Context): File {
        val dir = File(context.filesDir, APP_LOG_DIR_NAME)
        val parent = dir.parentFile
        val parentReady = parent == null || parent.exists() || parent.mkdirs()
        if (!parentReady || (!dir.exists() && !dir.mkdirs()) || !dir.isDirectory) {
            throw IOException("无法创建应用日志目录: ${dir.absolutePath}")
        }
        return dir
    }

    private fun externalLogDir(): File? = runCatching {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, EXTERNAL_FOLDER_NAME)
        if ((!dir.exists() && !dir.mkdirs()) || !dir.isDirectory) null else dir
    }.getOrNull()

    /** 记录应用日志；返回是否已写入并校验应用内日志文件。外部目录失败不会掩盖应用内成功。 */
    fun log(tag: String, message: String, throwable: Throwable? = null): Boolean {
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = buildString {
            append("[$time] [").append(tag).append("] ").append(message).append('\n')
            if (throwable != null) {
                val sw = StringWriter()
                PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
                append(sw.toString()).append('\n')
            }
        }
        val written = appendVerified(appLogDir(com.jarvis.pineapple.JarvisApp.instance.applicationContext), "jarvis_app_$date.log", line)
        if (!written) Log.e(TAG, "写入应用日志失败")
        runCatching { externalLogDir()?.let { appendVerified(it, "jarvis_app_$date.log", line) } }
        return written
    }

    /** 记录崩溃到 Download/JARVIS（尽力而为）；崩溃主记录由 [CrashRecorder] 持久化。 */
    fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = externalLogDir() ?: run {
            Log.w(TAG, "外部崩溃目录不可用，跳过 Download 崩溃副本")
            return
        }
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(dir, "jarvis_crash_$stamp.txt")
            val text = buildCrashText(context, thread, throwable)
            if (!writeVerifiedText(file, text)) throw IOException("外部崩溃文件校验失败")
            Log.e(TAG, "崩溃已保存到: ${file.absolutePath}")
        }.onFailure { Log.e(TAG, "崩溃写入 Download 失败", it) }
    }

    /** 记录启动信息；返回是否已写入并校验应用内启动日志。 */
    fun recordStartup(context: Context): Boolean {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val info = buildString {
            append("JARVIS 启动日志\n")
            append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
            append("包名: ${context.packageName}\n")
            append("设备: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("ABI: ${(Build.SUPPORTED_ABIS ?: emptyArray()).joinToString()}\n")
            append("ROM: ${Build.DISPLAY}\n")
            append("CPU_ABI: ${Build.CPU_ABI}\n")
        }
        val written = writeVerifiedText(File(appLogDir(context), "jarvis_startup_$stamp.txt"), info)
        if (!written) Log.e(TAG, "写入启动日志失败")
        return written
    }

    fun hasAppLogs(context: Context): Boolean = appLogFiles(context).isNotEmpty()

    fun appLogFiles(context: Context): List<File> = runtimeLogFiles(appLogDir(context))

    fun readAppLogs(context: Context): String = readVerifiedFiles(appLogFiles(context))

    fun exportLogs(context: Context): File? {
        val appFiles = appLogFiles(context)
        val crashFiles = CrashRecorder.list(context)
        if (appFiles.isEmpty() && crashFiles.isEmpty()) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val output = File(appLogDir(context), "jarvis_log_$stamp.txt")
        return exportLogsToFile(appFiles, crashFiles, output)
    }

    fun clearAppLogs(context: Context): Int {
        val files = appLogFiles(context)
        clearFiles(files)
        val remaining = appLogFiles(context).filter { it.exists() }
        if (remaining.isNotEmpty()) {
            throw IOException("应用日志未完全清空: ${remaining.joinToString { it.name }}")
        }
        return files.size
    }

    internal fun runtimeLogFiles(dir: File): List<File> {
        val files = dir.listFiles() ?: throw IOException("无法读取应用日志目录: ${dir.absolutePath}")
        return files
            .filter { file -> file.isFile && runtimeLogFileNames.any { it.matches(file.name) } }
            .sortedBy { it.name }
    }

    internal fun readVerifiedFiles(files: List<File>): String {
        if (files.isEmpty()) return "（无应用日志）"
        return files.joinToString("\n\n") { file ->
            "########## ${file.name} ##########\n${file.readText()}"
        }
    }

    internal fun exportLogsToFile(appFiles: List<File>, crashFiles: List<File>, output: File): File {
        val header = "JARVIS 日志导出\n时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n"
        val body = buildString {
            append(header)
            if (appFiles.isNotEmpty()) {
                append("\n===== 应用日志 =====\n")
                append(readVerifiedFiles(appFiles))
            }
            if (crashFiles.isNotEmpty()) {
                append("\n\n===== 崩溃记录 =====\n")
                append(CrashRecorder.readRecords(crashFiles))
            }
        }
        if (!writeVerifiedText(output, body)) {
            throw IOException("日志导出文件写入失败: ${output.absolutePath}")
        }
        val written = output.readText()
        if (!output.exists() || output.length() == 0L || !written.startsWith(header)) {
            output.delete()
            throw IOException("日志导出文件校验失败: ${output.absolutePath}")
        }
        return output
    }

    internal fun clearFiles(files: List<File>): Int {
        val failed = files.filter { it.exists() && !it.delete() }
        if (failed.isNotEmpty()) {
            throw IOException("${failed.size} 个应用日志文件删除失败: ${failed.joinToString { it.name }}")
        }
        return files.size
    }

    internal fun appendVerified(dir: File, fileName: String, text: String): Boolean = try {
        val file = File(dir, fileName)
        val before = if (file.exists()) file.length() else 0L
        val bytes = text.toByteArray(Charsets.UTF_8)
        FileOutputStream(file, true).use { it.write(bytes) }
        file.exists() && file.length() >= before + bytes.size
    } catch (_: IOException) {
        false
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

    private fun buildCrashText(context: Context, thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("=== JARVIS 崩溃报告 (Download) ===")
            pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            pw.println("包名: ${context.packageName}")
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
}

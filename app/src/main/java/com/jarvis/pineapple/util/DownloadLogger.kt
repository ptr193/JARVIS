package com.jarvis.pineapple.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载目录日志记录器。
 *
 * 真机测试场景：用户无法通过 adb 抓取 logcat，只能提供现象。
 * 本类直接把日志和崩溃堆栈写入手机的 Download/JARVIS/ 目录，
 * 用户可以通过文件管理器直接找到文件并传输，不依赖 USB 调试。
 */
object DownloadLogger {

    private const val TAG = "DownloadLogger"
    private const val FOLDER_NAME = "JARVIS"

    private fun logDir(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, FOLDER_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 记录应用日志到 Download/JARVIS/jarvis_app_<日期>.log */
    fun log(tag: String, message: String, throwable: Throwable? = null) {
        try {
            val dir = logDir()
            val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val file = File(dir, "jarvis_app_$date.log")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = StringBuilder()
            line.append("[$time] [").append(tag).append("] ").append(message).append("\n")
            if (throwable != null) {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                pw.close()
                line.append(sw.toString()).append("\n")
            }
            FileOutputStream(file, true).use { fos ->
                fos.write(line.toString().toByteArray(Charsets.UTF_8))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "写入 Download 日志失败", e)
        }
    }

    /** 记录崩溃到 Download/JARVIS/jarvis_crash_<时间戳>.txt */
    fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val dir = logDir()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(dir, "jarvis_crash_$stamp.txt")
            val sw = StringWriter()
            PrintWriter(sw).use { pw ->
                pw.println("=== JARVIS 崩溃报告 (Download) ===")
                pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                pw.println("线程: ${thread.name} (id=${thread.id})")
                pw.println("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
                pw.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                pw.println("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
                pw.println("ROM: ${Build.DISPLAY}")
                pw.println("----- 堆栈 -----")
                throwable.printStackTrace(pw)
            }
            file.writeText(sw.toString(), Charsets.UTF_8)
            Log.e(TAG, "崩溃已保存到: ${file.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "崩溃写入 Download 失败", e)
        }
    }

    /** 记录启动信息 */
    fun recordStartup(context: Context) {
        try {
            val dir = logDir()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "jarvis_startup_$stamp.txt")
            val info = StringBuilder()
            info.append("JARVIS 启动日志\n")
            info.append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
            info.append("包名: ${context.packageName}\n")
            info.append("设备: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            info.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            info.append("ABI: ${Build.SUPPORTED_ABIS.joinToString()}\n")
            info.append("ROM: ${Build.DISPLAY}\n")
            info.append("CPU_ABI: ${Build.CPU_ABI}\n")
            file.writeText(info.toString(), Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.e(TAG, "写入启动日志失败", e)
        }
    }
}

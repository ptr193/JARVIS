package com.jarvis.pineapple

import android.app.Application
import com.jarvis.pineapple.di.appModule
import com.jarvis.pineapple.plugin.PluginManager
import com.jarvis.pineapple.util.CrashRecorder
import com.jarvis.pineapple.util.DownloadLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * JARVIS application entry point.
 */
class JarvisApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        runCatching { DownloadLogger.recordStartup(this) }
        runCatching { DownloadLogger.log("JarvisApp", "onCreate 开始") }
        installCrashRecorder()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@JarvisApp)
            modules(appModule)
        }
        // 加载并启用已安装插件（#139）；失败不影响主流程
        runCatching {
            org.koin.core.context.GlobalContext.get().get<PluginManager>().reload()
        }.onFailure {
            android.util.Log.w("JarvisApp", "插件加载失败: ${it.message}")
            runCatching { DownloadLogger.log("JarvisApp", "插件加载失败", it) }
        }
        runCatching { DownloadLogger.log("JarvisApp", "onCreate 完成，等待 MainActivity 启动") }
        // Application.onCreate 执行时应用尚未进入前台，在 Android 12+ 上启动前台服务
        // 属于后台启动，会被系统拒绝甚至导致进程被杀。改由 MainActivity 在确定进入
        // 前台后再启动（见 MainActivity.onStart）。
    }

    /**
     * 安装全局未捕获异常处理器。
     *
     * 背景（BUG-2026-1029）：实机上应用「短暂显示主界面后闪退，之后再也打不开」，
     * 但既无 logcat 也无 tombstone。此处理器把崩溃堆栈写入 `filesDir/crash/`，
     * 用户下次打开后可在应用内导出，从而拿到定位所需的第一手证据。
     *
     * 注意：记录后必须把异常继续交回上一个处理器（通常是系统默认处理器），
     * 否则进程不会正常终止，会表现为「卡死」而非「闪退」，反而更难排查。
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 先尽力记录（内部已做异常隔离，不会二次抛出）
            runCatching { CrashRecorder.record(this, thread, throwable) }
            runCatching { DownloadLogger.recordCrash(this, thread, throwable) }
                .onFailure { android.util.Log.e("JarvisApp", "崩溃记录失败", it) }
            // 再交回系统默认处理器，保证行为与原生崩溃一致（进程被终止 + logcat 留痕）
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        lateinit var instance: JarvisApp
            private set
    }
}

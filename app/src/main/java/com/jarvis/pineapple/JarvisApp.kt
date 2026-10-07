package com.jarvis.pineapple

import android.app.Application
import com.jarvis.pineapple.di.appModule
import com.jarvis.pineapple.service.KeepAliveService
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * JARVIS application entry point.
 * Initializes dependency injection (Koin) and global services.
 */
class JarvisApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@JarvisApp)
            modules(appModule)
        }
        // 启动保活前台服务
        KeepAliveService.start(this)
    }

    companion object {
        lateinit var instance: JarvisApp
            private set
    }
}

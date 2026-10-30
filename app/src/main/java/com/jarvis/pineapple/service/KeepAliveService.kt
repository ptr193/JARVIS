package com.jarvis.pineapple.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.pineapple.ui.MainActivity
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 保活前台服务。
 * - 常驻通知（带唤醒按钮）
 * - 进程被杀后通过重启机制恢复
 * - 支持麦克风常驻以监听唤醒词
 */
class KeepAliveService : Service() {

    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )
    private var autonomousJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 【关键】startForeground() 必须是 onCreate 中最先完成的动作。
        //
        // 系统规定：调用 startForegroundService() 后，服务必须在约 5 秒内调用
        // startForeground()，否则抛 RemoteServiceException 并杀死进程。
        // 注意：该 5 秒时钟从 startForegroundService() 被调用时开始计时，
        // 而 Service.onCreate 回调同样排在主线程消息队列里。因此仅把本调用放在
        // onCreate 开头还不够，调用方还必须确保主线程不被长时间占用
        // （见 MainActivity：唤醒引擎改到后台线程、服务延后至首帧后启动）。
        runCatching { createChannel() }
        startForeground(NOTIFICATION_ID, buildNotification())
        // 唤醒引擎启动涉及麦克风/传感器等，可能与主线程竞争，放到后台线程执行
        serviceScope.launch {
            runCatching {
                org.koin.core.context.GlobalContext.get()
                    .get<com.jarvis.pineapple.wake.WakeEngine>()
                    .start()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 防御性兜底：若服务被系统以 START_STICKY 拉起（onCreate 可能因进程状态未走到
        // startForeground，或时限被系统重置），此处再次调用可确保满足前台服务要求。
        // startForeground 幂等，重复调用无副作用。
        runCatching {
            createChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        // 启动自主任务调度循环（#117 自主任务执行）+ 智能家居场景触发轮询（#85）
        startAutonomousLoop()
        // START_STICKY：进程被杀后系统自动重启服务
        return START_STICKY
    }

    /** 周期执行自主任务调度与场景触发检查。 */
    private fun startAutonomousLoop() {
        if (autonomousJob?.isActive == true) return
        autonomousJob = serviceScope.launch {
            // 首轮延后执行：避免在应用冷启动的关键窗口内触发
            // AutonomousDecisionManager / SmartHomeManager 的类加载与校验
            // （实测日志显示这些方法校验各耗时 100-500ms，会加剧启动卡顿）。
            kotlinx.coroutines.delay(AUTONOMOUS_INITIAL_DELAY_MS)
            while (isActive) {
                runCatching {
                    org.koin.core.context.GlobalContext.get()
                        .get<com.jarvis.pineapple.autonomous.AutonomousDecisionManager>()
                        .checkAndExecuteTasks()
                }
                runCatching {
                    org.koin.core.context.GlobalContext.get()
                        .get<com.jarvis.pineapple.smarthome.SmartHomeManager>()
                        .checkTriggers(currentLocation = null)
                }
                kotlinx.coroutines.delay(AUTONOMOUS_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        autonomousJob?.cancel()
        // 进程/服务销毁时停止唤醒器，避免泄漏；START_STICKY 重建后会再次启动
        runCatching {
            org.koin.core.context.GlobalContext.get()
                .get<com.jarvis.pineapple.wake.WakeEngine>()
                .stop()
        }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "JARVIS 常驻服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "JARVIS 后台保活与唤醒监听" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val wakeIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent("com.jarvis.pineapple.WAKE").setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS 待命")
            .setContentText("随时为您服务")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_play, "唤醒", wakeIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_keepalive"
        private const val NOTIFICATION_ID = 1001

        /** 自主任务调度与场景触发轮询间隔（60 秒）。 */
        private const val AUTONOMOUS_INTERVAL_MS = 60_000L

        /** 自主循环首轮延迟（15 秒），避开冷启动关键窗口。 */
        private const val AUTONOMOUS_INITIAL_DELAY_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 安全启动：任何异常（含 Android 12+ 后台启动前台服务被拒）都只记录日志，
         * 不向上抛出，避免拖垮调用方（MainActivity/BootReceiver）的启动流程。
         */
        fun startSafely(context: Context, tag: String = "KeepAliveService") {
            runCatching { start(context) }.onFailure {
                android.util.Log.w(tag, "启动保活服务失败: ${it.message}")
            }
        }
    }
}

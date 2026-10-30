package com.jarvis.pineapple.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.pineapple.service.KeepAliveService

/**
 * 开机自启与应用更新后自启，恢复保活服务。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 开机/更新后自启：属于允许的后台启动场景，但厂商限制仍可能抛异常，故安全启动
                KeepAliveService.startSafely(context, "BootReceiver")
            }
            "com.jarvis.pineapple.WAKE" -> {
                // 通知栏唤醒按钮
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                launch?.let { context.startActivity(it) }
            }
        }
    }
}

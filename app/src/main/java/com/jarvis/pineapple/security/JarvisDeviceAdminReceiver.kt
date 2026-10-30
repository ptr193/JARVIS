package com.jarvis.pineapple.security

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * JARVIS 设备管理员接收器（防盗功能）。
 *
 * 激活后可使用 DevicePolicyManager 执行远程锁定屏幕、擦除数据等操作。
 */
class JarvisDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "设备管理员已激活")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "设备管理员已停用")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "停用后 JARVIS 将无法远程锁定或擦除本设备（防盗功能失效）。"
    }

    companion object {
        private const val TAG = "JarvisDeviceAdmin"

        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, JarvisDeviceAdminReceiver::class.java)
    }
}

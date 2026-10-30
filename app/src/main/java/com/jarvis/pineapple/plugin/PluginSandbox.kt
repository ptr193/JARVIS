package com.jarvis.pineapple.plugin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 插件沙箱（#139 安全程序分化）。
 *
 * 插件只能通过本类提供的 [PluginHost] 访问宿主能力；每个能力在调用前都会
 * 依据插件声明的权限集做校验，未授权的调用被拒绝并记录日志（不静默放行）。
 *
 * 说明：Android 没有通用的 JVM 级沙箱来阻止任意反射/系统调用，因此本沙箱
 * 在宿主侧对"通过宿主暴露的能力"实施强制校验；插件自身的代码隔离还依赖
 * 签名校验（见 [PluginLoader.verifySignature]）与独立 [android.content.Context]
 * 存储目录。
 */
class PluginSandbox(
    override val pluginId: String,
    private val context: Context,
    private val grantedCapabilities: Set<String>
) : PluginHost {

    private val tag = "Plugin[$pluginId]"

    override fun log(message: String) {
        Log.i(tag, message)
    }

    override fun isCapabilityGranted(capability: String): Boolean =
        capability in grantedCapabilities

    override fun getPreference(key: String, default: String?): String? {
        if (!isCapabilityGranted(PluginCapability.STORAGE)) {
            Log.w(tag, "拒绝偏好读取：缺少 storage 能力")
            return default
        }
        return prefs().getString(key, default)
    }

    override fun setPreference(key: String, value: String) {
        if (!isCapabilityGranted(PluginCapability.STORAGE)) {
            Log.w(tag, "拒绝偏好写入：缺少 storage 能力")
            return
        }
        prefs().edit().putString(key, value).apply()
    }

    override fun pluginFilesDir(): File {
        val dir = File(context.filesDir, "plugin_data/$pluginId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun httpGet(url: String, timeoutMs: Int): String? {
        if (!isCapabilityGranted(PluginCapability.NETWORK)) {
            Log.w(tag, "拒绝网络请求：缺少 network 能力")
            return null
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            Log.w(tag, "拒绝非法 URL: $url")
            return null
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            Log.w(tag, "HTTP GET 失败: ${e.message}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    override fun notify(title: String, message: String) {
        if (!isCapabilityGranted(PluginCapability.NOTIFICATIONS)) {
            Log.w(tag, "拒绝发送通知：缺少 notifications 能力")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "插件通知", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        manager.notify(
            notificationId(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun prefs() =
        context.getSharedPreferences("plugin_$pluginId", Context.MODE_PRIVATE)

    private fun notificationId(): Int = pluginId.hashCode() and 0x00FFFFFF

    companion object {
        private const val CHANNEL_ID = "jarvis_plugins"
    }
}

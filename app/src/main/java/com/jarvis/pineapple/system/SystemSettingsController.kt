package com.jarvis.pineapple.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings

/**
 * 系统设置控制：WiFi/蓝牙/飞行模式、截屏、打开任意 App、媒体控制
 */
class SystemSettingsController(
    private val context: Context,
    private val screenshotManager: ScreenshotManager? = null
) {

    @SuppressLint("MissingPermission")
    fun setWifiEnabled(enabled: Boolean): Boolean {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CHANGE_WIFI_STATE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!PermissionGate.wifiChangeAllowed(granted)) return false
        @Suppress("DEPRECATION")
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifi.isWifiEnabled = enabled
        return true
    }

    @SuppressLint("MissingPermission")
    fun setBluetoothEnabled(enabled: Boolean): Boolean {
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.Manifest.permission.BLUETOOTH_CONNECT
        } else {
            android.Manifest.permission.BLUETOOTH_ADMIN
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!PermissionGate.bluetoothChangeAllowed(granted)) return false
        @Suppress("DEPRECATION")
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (enabled) adapter?.enable() else adapter?.disable()
        return true
    }

    fun toggleAirplaneMode() {
        val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * 截屏：通过 MediaProjection 截取屏幕并保存为 PNG。
     *
     * 需先经 [ScreenshotManager.requestCapturePermission] 获得用户授权；
     * 未授权或无管理器时返回 null（调用方应引导用户授权）。
     */
    fun takeScreenshot(): java.io.File? {
        val manager = screenshotManager ?: return null
        if (!manager.hasPermission()) return null
        return manager.captureToFile()
    }

    /** 媒体控制 */
    fun playPause() {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        }
        context.sendOrderedBroadcast(intent, null)
    }

    fun setVolume(volume: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
    }

    fun setBrightness(brightness: Int): Boolean {
        if (!PermissionGate.brightnessWriteAllowed(Settings.System.canWrite(context))) return false
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
        return true
    }
}

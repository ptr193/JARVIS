package com.jarvis.pineapple.system

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings

/**
 * 系统设置控制：WiFi/蓝牙/飞行模式、截屏、打开任意 App、媒体控制
 */
class SystemSettingsController(private val context: Context) {

    fun setWifiEnabled(enabled: Boolean) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifi.isWifiEnabled = enabled
    }

    fun setBluetoothEnabled(enabled: Boolean) {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (enabled) adapter?.enable() else adapter?.disable()
    }

    fun toggleAirplaneMode() {
        // 需要系统权限，此处引导用户
        val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openApp(packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }

    fun takeScreenshot() {
        // 需要 MediaProjection 权限，此处占位
    }

    /** 媒体控制 */
    fun playPause() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            // 通过 MediaSession 控制
        }
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        }
        context.sendOrderedBroadcast(intent, null)
    }

    fun setVolume(volume: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
    }

    fun setBrightness(brightness: Int) {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
    }
}

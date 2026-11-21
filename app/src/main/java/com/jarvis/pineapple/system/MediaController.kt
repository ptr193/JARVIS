package com.jarvis.pineapple.system

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager

/**
 * 媒体控制：音量/亮度/播放暂停（#55）
 */
class MediaController(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 获取当前音量 (0-15) */
    fun getVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    /** 设置音量 (0-15) */
    fun setVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 15)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
    }

    /** 音量+ */
    fun volumeUp() = setVolume(getVolume() + 1)

    /** 音量- */
    fun volumeDown() = setVolume(getVolume() - 1)

    /** 静音/取消静音 */
    fun toggleMute() {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_TOGGLE_MUTE,
            0
        )
    }

    /** 获取屏幕亮度 (0-255) */
    fun getBrightness(): Int {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Settings.SettingNotFoundException) {
            128
        }
    }

    /** 设置屏幕亮度 (0-255) */
    fun setBrightness(brightness: Int): Boolean {
        if (!PermissionGate.brightnessWriteAllowed(Settings.System.canWrite(context))) return false
        val clamped = brightness.coerceIn(0, 255)
        return runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                clamped
            )
        }.getOrDefault(false)
    }

    /** 向当前媒体会话发送完整按键事件；返回是否成功交给系统分发。 */
    private fun dispatchMediaKey(keyCode: Int): Boolean = runCatching {
        audioManager.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        )
        audioManager.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
        )
        true
    }.getOrDefault(false)

    fun playPause(): Boolean = dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    fun next(): Boolean = dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)

    fun previous(): Boolean = dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
}

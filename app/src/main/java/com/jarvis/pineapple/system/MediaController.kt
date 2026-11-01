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
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            clamped
        )
        return true
    }

    /** 播放/暂停（通过媒体会话） */
    fun playPause() {
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    /** 下一曲 */
    fun next() {
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
    }

    /** 上一曲 */
    fun previous() {
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
    }
}

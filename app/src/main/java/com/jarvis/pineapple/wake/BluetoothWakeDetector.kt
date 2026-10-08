package com.jarvis.pineapple.wake

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 蓝牙按键唤醒：BluetoothHeadset 监听 + MediaSession 按键事件
 */
class BluetoothWakeDetector(private val context: Context) {

    private var onWake: (() -> Unit)? = null
    private var receiver: android.content.BroadcastReceiver? = null

    fun start(callback: () -> Unit) {
        onWake = callback
        receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED,
                    Intent.ACTION_MEDIA_BUTTON -> {
                        onWake?.invoke()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(Intent.ACTION_MEDIA_BUTTON)
        }
        // Android 13+ (Tiramisu/33) requires explicit exported flag for non-system broadcasts
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.RECEIVER_NOT_EXPORTED
        } else 0
        ContextCompat.registerReceiver(context, receiver, filter, flags)
    }

    fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }
}

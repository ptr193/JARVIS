package com.jarvis.pineapple.wake

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 蓝牙设备按键唤醒：监听蓝牙耳机/设备的按键事件。
 *
 * 通过监听蓝牙耳机厂商特定事件（[BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT]）
 * 捕获耳机上的多功能按键，实现免手操作唤醒。
 */
class BluetoothWakeDetector(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothWakeDetector"
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val _detected = MutableStateFlow(false)
    val detected: StateFlow<Boolean> = _detected.asStateFlow()

    private var registered = false

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> {
                    @Suppress("DEPRECATION")
                    val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        Log.i(TAG, "捕获蓝牙耳机按键：${KeyEvent.keyCodeToString(keyEvent.keyCode)}")
                        _detected.value = true
                    }
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1)
                    Log.i(TAG, "蓝牙耳机连接状态变化：$state")
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", 0)
                    Log.i(TAG, "有线耳机插入状态：$state")
                }
            }
        }
    }

    fun start() {
        if (bluetoothAdapter == null) {
            Log.w(TAG, "设备不支持蓝牙，跳过蓝牙唤醒")
            return
        }
        if (registered) return

        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ContextCompat.registerReceiver(
                    context,
                    broadcastReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
            }
            registered = true
        } catch (e: Exception) {
            Log.e(TAG, "注册蓝牙广播接收器失败", e)
        }
    }

    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(broadcastReceiver)
        } catch (e: Exception) {
            // 忽略未注册异常
        }
        registered = false
    }

    fun onHeadsetButtonPressed() {
        _detected.value = true
    }

    fun clear() {
        _detected.value = false
    }
}

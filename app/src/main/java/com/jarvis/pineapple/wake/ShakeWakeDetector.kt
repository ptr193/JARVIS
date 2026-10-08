package com.jarvis.pineapple.wake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * 摇一摇唤醒：加速度传感器 + 晃动算法 + 防误触（阈值+冷却）
 */
class ShakeWakeDetector(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var lastShakeTime = 0L
    private val cooldownMs = 3000L
    private val threshold = 15.0

    private var onShake: (() -> Unit)? = null

    fun start(callback: () -> Unit) {
        onShake = callback
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
        val now = System.currentTimeMillis()
        if (magnitude > threshold && now - lastShakeTime > cooldownMs) {
            lastShakeTime = now
            onShake?.invoke()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

package com.jarvis.pineapple.wake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 摇一摇唤醒：通过加速度传感器检测摇动手势。
 */
class ShakeWakeDetector(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var job: Job? = null
    private var lastShakeTime = 0L
    private var shakeCount = 0
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f

    private val _detected = MutableStateFlow(false)
    val detected: StateFlow<Boolean> = _detected.asStateFlow()

    fun start() {
        if (accelerometer == null) return
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        job?.cancel()
        job = null
        sensorManager.unregisterListener(this)
        shakeCount = 0
    }

    /** 读取并清除唤醒标记。 */
    fun consume(): Boolean {
        if (!_detected.value) return false
        _detected.value = false
        return true
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val deltaX = Math.abs(x - lastX)
        val deltaY = Math.abs(y - lastY)
        val deltaZ = Math.abs(z - lastZ)

        val threshold = 15f
        val now = System.currentTimeMillis()

        if ((deltaX > threshold || deltaY > threshold || deltaZ > threshold)
            && now - lastShakeTime > 1000
        ) {
            shakeCount++
            lastShakeTime = now
            if (shakeCount >= 3) {
                _detected.value = true
                shakeCount = 0
            }
        }

        lastX = x
        lastY = y
        lastZ = z
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

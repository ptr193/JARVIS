package com.jarvis.pineapple.perception

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 环境感知：场景识别、环境光/温度/湿度、手机姿态
 */
class EnvironmentPerception(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val _lightLevel = MutableStateFlow(0f)
    val lightLevel = _lightLevel.asStateFlow()

    private val _orientation = MutableStateFlow(floatArrayOf(0f, 0f, 0f))
    val orientation = _orientation.asStateFlow()

    fun start() {
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_LIGHT -> _lightLevel.value = event.values[0]
            Sensor.TYPE_ACCELEROMETER -> _orientation.value = event.values.copyOf()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** 摄像头场景识别（预留 ML Kit） */
    fun recognizeScene(): String = "未识别"
}

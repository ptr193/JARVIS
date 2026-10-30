package com.jarvis.pineapple.perception

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
import java.util.concurrent.TimeUnit

/**
 * 环境感知：场景识别、环境光/温度/湿度、手机姿态
 */
class EnvironmentPerception(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val _lightLevel = MutableStateFlow(0f)
    val lightLevel = _lightLevel.asStateFlow()

    private val _orientation = MutableStateFlow(floatArrayOf(0f, 0f, 0f))
    val orientation = _orientation.asStateFlow()

    // 场景识别数据
    private val _sceneData = MutableStateFlow(SceneData())
    val sceneData = _sceneData.asStateFlow()

    fun start() {
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() = sensorManager.unregisterListener(this)

    private var accelerometerValues: FloatArray? = null
    private var magneticValues: FloatArray? = null

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_LIGHT -> {
                _lightLevel.value = event.values[0]
                updateSceneData()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                accelerometerValues = event.values.copyOf()
                _orientation.value = event.values.copyOf()
                updateOrientation()
                updateSceneData()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticValues = event.values.copyOf()
                updateOrientation()
                updateSceneData()
            }
            Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                _sceneData.value = _sceneData.value.copy(
                    temperature = event.values[0].roundToInt().toFloat()
                )
            }
            Sensor.TYPE_RELATIVE_HUMIDITY -> {
                _sceneData.value = _sceneData.value.copy(
                    humidity = event.values[0].roundToInt().toFloat()
                )
            }
            Sensor.TYPE_PRESSURE -> {
                _sceneData.value = _sceneData.value.copy(
                    pressure = event.values[0]
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * 摄像头场景识别：使用 ML Kit 物体检测对图像进行分析。
     *
     * @param bitmap 摄像头采集的图像
     * @return 检测到的物体标签与置信度
     */
    fun recognizeScene(bitmap: Bitmap): String {
        return try {
            val options = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
            val detector = ObjectDetection.getClient(options)
            val objects = try {
                Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 5, TimeUnit.SECONDS)
            } finally {
                detector.close()
            }
            if (objects.isEmpty()) return "未检测到明显物体"
            val labels = objects.flatMap { it.labels }
                .sortedByDescending { it.confidence }
                .take(3)
            if (labels.isEmpty()) {
                "检测到 ${objects.size} 个物体（无分类标签）"
            } else {
                labels.joinToString("、") { "${it.text} ${(it.confidence * 100).toInt()}%" }
            }
        } catch (e: Exception) {
            "场景识别失败：${e.message}"
        }
    }

    /**
     * 基于光照/姿态传感器的场景推断。
     */
    fun recognizeScene(): String {
        // 基于传感器数据的简单场景推断
        val light = _lightLevel.value
        val orientation = _orientation.value
        val z = orientation.getOrElse(2) { 0f }

        return when {
            light < 10 -> "暗光环境"
            light > 10000 -> "强光/户外环境"
            z > 9f -> "手机平放（可能在桌面）"
            else -> "室内正常环境"
        }
    }

    private fun updateOrientation() {
        val accel = accelerometerValues
        val mag = magneticValues
        if (accel != null && mag != null) {
            val rotationMatrix = FloatArray(9)
            val success = SensorManager.getRotationMatrix(rotationMatrix, null, accel, mag)
            if (success) {
                val orientationVals = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientationVals)
                _orientation.value = orientationVals.copyOf()
            }
        }
    }

    private fun updateSceneData() {
        val light = _lightLevel.value
        val orient = _orientation.value
        val pitch = kotlin.math.atan2(orient[1].toDouble(), kotlin.math.sqrt((orient[0] * orient[0] + orient[2] * orient[2]).toDouble())) * 180 / kotlin.math.PI
        val roll = kotlin.math.atan2(orient[0].toDouble(), kotlin.math.sqrt((orient[1] * orient[1] + orient[2] * orient[2]).toDouble())) * 180 / kotlin.math.PI

        _sceneData.value = _sceneData.value.copy(
            lightLevel = light,
            pitch = pitch.toFloat(),
            roll = roll.toFloat(),
            isFlat = kotlin.math.abs(pitch) < 15f && kotlin.math.abs(roll) < 15f
        )
    }

    data class SceneData(
        val lightLevel: Float = 0f,
        val temperature: Float = 0f,
        val humidity: Float = 0f,
        val pressure: Float = 0f,
        val pitch: Float = 0f,
        val roll: Float = 0f,
        val isFlat: Boolean = false
    )
}

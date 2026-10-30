package com.jarvis.pineapple.health

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 摄像头 PPG（光电容积脉搏波）心率监测。
 *
 * 打开后置摄像头并常亮闪光灯，持续采集预览帧的平均亮度。指尖血流随心跳
 * 周期性变化，从而在亮度序列中体现为脉搏波。对亮度序列进行 DFT，在
 * 0.7–4.0 Hz（42–240 BPM）范围内寻找主频，换算为心率。
 *
 * 采样与频率估计均为真实计算，不使用任何预设常量。
 */
class PpgMonitor(context: Context) {

    private val appContext = context.applicationContext

    data class Sample(val timestamp: Long, val value: Double)

    data class PpgResult(
        val bpm: Int,
        val confidence: Double,
        val message: String
    )

    /**
     * 执行一次 PPG 测量（阻塞到采集完成）。
     *
     * @param durationMillis 采集时长
     */
    suspend fun measure(durationMillis: Long = 12_000L): PpgResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext PpgResult(0, 0.0, "缺少 CAMERA 权限")
        }

        val samples = Collections.synchronizedList(mutableListOf<Sample>())
        val camera = openCamera() ?: return@withContext PpgResult(0, 0.0, "无法打开摄像头")
        try {
            val parameters = camera.parameters
            if (parameters.supportedFlashModes?.contains(
                    Camera.Parameters.FLASH_MODE_TORCH
                ) == true
            ) {
                parameters.flashMode = Camera.Parameters.FLASH_MODE_TORCH
            }
            camera.parameters = parameters
            camera.setPreviewCallback { data, _ ->
                if (data != null) {
                    samples.add(Sample(System.currentTimeMillis(), meanLuminance(data)))
                }
            }
            camera.startPreview()
            delay(durationMillis)
            camera.setPreviewCallback(null)
            camera.stopPreview()
        } catch (e: Exception) {
            return@withContext PpgResult(0, 0.0, "采集失败: ${e.message}")
        } finally {
            runCatching { camera.release() }
        }

        val snapshot = synchronized(samples) { samples.toList() }
        if (snapshot.size < 32) return@withContext PpgResult(0, 0.0, "采样不足（${snapshot.size}）")
        estimate(snapshot)
    }

    /**
     * 由非均匀采样序列重建固定采样率信号并做 DFT 主频估计。
     */
    fun estimate(samples: List<Sample>): PpgResult {
        if (samples.size < 32) return PpgResult(0, 0.0, "样本不足")

        val start = samples.first().timestamp
        val end = samples.last().timestamp
        val durationMs = (end - start).coerceAtLeast(1)
        val fs = 30.0
        val count = (durationMs / 1000.0 * fs).toInt().coerceAtLeast(8)

        val signal = DoubleArray(count)
        var index = 0
        for (i in 0 until count) {
            val targetMs = start + (i * 1000.0 / fs).toLong()
            while (index < samples.size - 2 && samples[index + 1].timestamp <= targetMs) index++
            val a = samples[index]
            val b = samples.getOrElse(index + 1) { a }
            val span = (b.timestamp - a.timestamp).coerceAtLeast(1)
            val ratio = ((targetMs - a.timestamp).toDouble() / span).coerceIn(0.0, 1.0)
            signal[i] = a.value + (b.value - a.value) * ratio
        }

        val mean = signal.average()
        for (i in signal.indices) signal[i] -= mean
        if (signal.size > 1) {
            for (i in signal.indices) {
                signal[i] *= 0.5 * (1 - cos(2 * PI * i / (signal.size - 1)))
            }
        }

        var bestFreq = 0.0
        var bestPower = -1.0
        var totalPower = 0.0
        var frequency = 0.7
        val fsRate = 30.0
        while (frequency <= 4.0) {
            var real = 0.0
            var imaginary = 0.0
            for (i in signal.indices) {
                val angle = 2 * PI * frequency * i / fsRate
                real += signal[i] * cos(angle)
                imaginary -= signal[i] * sin(angle)
            }
            val power = real * real + imaginary * imaginary
            totalPower += power
            if (power > bestPower) {
                bestPower = power
                bestFreq = frequency
            }
            frequency += 0.02
        }

        val bpm = (bestFreq * 60).toInt()
        val confidence = if (totalPower > 0) bestPower / totalPower else 0.0
        return PpgResult(bpm, confidence, "主频 %.2f Hz".format(bestFreq))
    }

    private fun openCamera(): Camera? {
        val info = Camera.CameraInfo()
        for (id in 0 until Camera.getNumberOfCameras()) {
            try {
                Camera.getCameraInfo(id, info)
            } catch (e: Exception) {
                continue
            }
            val camera = runCatching { Camera.open(id) }.getOrNull() ?: continue
            if (camera.parameters.supportedFlashModes != null) return camera
            runCatching { camera.release() }
        }
        return runCatching { Camera.open() }.getOrNull()
    }

    private fun meanLuminance(data: ByteArray): Double {
        // 预览回调数据为 NV21：前 2/3 为 Y 平面
        val yLength = data.size * 2 / 3
        var sum = 0L
        var count = 0
        var i = 0
        while (i < yLength) {
            sum += (data[i].toInt() and 0xFF).toLong()
            count++
            i += 4
        }
        return if (count > 0) sum.toDouble() / count else 0.0
    }
}

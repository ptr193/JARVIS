package com.jarvis.pineapple.system

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * 设备控制：振动与手电筒（#78 / #79）。
 *
 * 全部基于真实 Android 平台 API（Vibrator / CameraManager.setTorchMode）。
 * 失败一律返回 false 并提示，不允许“调用没抛异常就算成功”。
 * 注意：JVM 单测只能验证纯决策逻辑，振动/闪光灯的真实效果需真机验收。
 */
class DeviceControlManager(private val context: Context) {

    private var torchCameraId: String? = null

    private fun obtainVibrator(): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) {
        null
    }

    fun hasVibrator(): Boolean = obtainVibrator()?.hasVibrator() == true

    /** 触发一次振动；返回是否已成功向系统下发振动请求。 */
    fun vibrate(durationMs: Long = 200): Boolean {
        if (!isVibrationDurationValid(durationMs)) return false
        return try {
            val vibrator = obtainVibrator() ?: return false
            if (!vibrator.hasVibrator()) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun cancelVibration(): Boolean = obtainVibrator()?.let { it.cancel(); true } ?: false

    fun hasTorch(): Boolean = try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        torchCameras(cm).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    /** 开/关手电筒；返回是否已成功下发。相机关闭/占用或无闪光灯时返回 false。 */
    fun setTorchEnabled(enabled: Boolean): Boolean {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return false
            val cameras = torchCameras(cm)
            val id = torchCameraId?.takeIf { it in cameras.map { it.id } }
                ?: selectTorchCamera(cameras)
                ?: return false
            torchCameraId = id
            cm.setTorchMode(id, enabled)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun torchCameras(cm: CameraManager): List<CameraInfo> =
        cm.cameraIdList.map { id ->
            val hasFlash = cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            CameraInfo(id, hasFlash)
        }

    data class CameraInfo(val id: String, val hasFlashUnit: Boolean)

    companion object {
        /** 选择第一个带闪光灯的相机 id（纯逻辑，便于单测）。 */
        fun selectTorchCamera(cameras: List<CameraInfo>): String? =
            cameras.firstOrNull { it.hasFlashUnit }?.id

        /** 振动时长必须在合理区间，避免异常值。 */
        fun isVibrationDurationValid(durationMs: Long): Boolean =
            durationMs in 1L..60_000L
    }
}

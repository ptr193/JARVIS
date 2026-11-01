package com.jarvis.pineapple.perception

import android.content.Context
import android.graphics.Bitmap

/**
 * 统一目标检测入口。
 *
 * 内部委托 [MLKitObjectDetector]（Google ML Kit，真实推理）完成检测，
 * 并提供威胁等级判定（陌生人/危险物品，功能 #105~#107）。
 */
class ObjectDetector(context: Context) {

    private val detector = MLKitObjectDetector(context)

    data class DetectedObject(val label: String, val confidence: Float, val boundingBox: RectF)

    data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** 多目标标记（真实 ML Kit 推理） */
    suspend fun detect(bitmap: Bitmap): List<DetectedObject> {
        val result = detector.detect(bitmap)
        return result.objects.map { obj ->
            DetectedObject(
                label = obj.label,
                confidence = obj.confidence,
                boundingBox = RectF(
                    obj.boundingBox.left.toFloat(),
                    obj.boundingBox.top.toFloat(),
                    obj.boundingBox.right.toFloat(),
                    obj.boundingBox.bottom.toFloat()
                )
            )
        }
    }

    /** 威胁探测：陌生人/危险物品（委托纯函数，便于单元测试） */
    suspend fun detectThreat(objects: List<DetectedObject>): ThreatLevel = classifyThreat(objects)

    /** 释放 ML Kit 检测器资源 */
    fun close() {
        detector.close()
    }

    enum class ThreatLevel { LOW, MEDIUM, HIGH }

    companion object {
        private val DANGEROUS_LABELS = setOf("knife", "gun", "weapon", "fire", "smoke")
        const val CAMERA_PERMISSION_DENIED = "无相机权限，无法进行目标检测"

        fun ensureCameraPermission(granted: Boolean): String? =
            if (granted) null else CAMERA_PERMISSION_DENIED


        /** 威胁等级判定（纯函数，无 Android 依赖） */
        fun classifyThreat(objects: List<DetectedObject>): ThreatLevel {
            val dangerous = objects.any { it.label in DANGEROUS_LABELS }
            return when {
                dangerous -> ThreatLevel.HIGH
                objects.any { it.label == "person" } -> ThreatLevel.MEDIUM
                else -> ThreatLevel.LOW
            }
        }
    }
}

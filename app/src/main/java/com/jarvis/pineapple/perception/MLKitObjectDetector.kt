package com.jarvis.pineapple.perception

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetectorOptionsBase
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ML Kit 目标检测器（功能 #105, #106, #107）。
 *
 * 使用 Google ML Kit Object Detection API 实现真实的目标检测。
 */
class MLKitObjectDetector(private val context: Context) {

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptionsBase.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    /**
     * 检测结果
     */
    data class ObjectDetectionResult(
        val isDetected: Boolean,
        val objects: List<DetectedObject>,
        val inferenceTimeMs: Long
    )

    data class DetectedObject(
        val label: String,
        val confidence: Float,
        val boundingBox: android.graphics.Rect,
        val trackingId: Int? = null,
        val labels: List<ClassificationLabel> = emptyList()
    )

    data class ClassificationLabel(
        val text: String,
        val confidence: Float,
        val index: Int
    )

    /**
     * 检测目标
     */
    suspend fun detect(bitmap: Bitmap): ObjectDetectionResult = suspendCancellableCoroutine { continuation ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val startTime = System.currentTimeMillis()

        detector.process(inputImage)
            .addOnSuccessListener { objects ->
                val inferenceTime = System.currentTimeMillis() - startTime

                val detectedObjects = objects.map { obj ->
                    DetectedObject(
                        label = obj.labels.firstOrNull()?.text ?: "Unknown",
                        confidence = obj.labels.firstOrNull()?.confidence ?: 0f,
                        boundingBox = obj.boundingBox,
                        trackingId = obj.trackingId,
                        labels = obj.labels.map { label ->
                            ClassificationLabel(
                                text = label.text,
                                confidence = label.confidence,
                                index = label.index
                            )
                        }
                    )
                }

                val result = ObjectDetectionResult(
                    isDetected = objects.isNotEmpty(),
                    objects = detectedObjects,
                    inferenceTimeMs = inferenceTime
                )

                continuation.resume(result)
            }
            .addOnFailureListener {
                continuation.resume(
                    ObjectDetectionResult(
                        isDetected = false,
                        objects = emptyList(),
                        inferenceTimeMs = System.currentTimeMillis() - startTime
                    )
                )
            }
    }

    /**
     * 检测特定类别的目标
     */
    suspend fun detectByCategory(bitmap: Bitmap, categories: List<String>): ObjectDetectionResult {
        val result = detect(bitmap)
        if (!result.isDetected) return result

        val filteredObjects = result.objects.filter { obj ->
            categories.any { category ->
                obj.label.contains(category, ignoreCase = true)
            }
        }

        return result.copy(objects = filteredObjects)
    }

    /**
     * 检测危险物品
     */
    suspend fun detectDangerousObjects(bitmap: Bitmap): ObjectDetectionResult {
        return detectByCategory(
            bitmap,
            listOf("knife", "gun", "weapon", "fire", "smoke", "danger")
        )
    }

    /**
     * 检测人脸
     */
    suspend fun detectFaces(bitmap: Bitmap): ObjectDetectionResult {
        return detectByCategory(bitmap, listOf("face", "person"))
    }

    /**
     * 获取最高置信度的目标
     */
    fun getTopObject(result: ObjectDetectionResult): DetectedObject? {
        return result.objects.maxByOrNull { it.confidence }
    }

    /**
     * 获取指定标签的目标
     */
    fun getObjectsByLabel(result: ObjectDetectionResult, label: String): List<DetectedObject> {
        return result.objects.filter { it.label.contains(label, ignoreCase = true) }
    }

    /**
     * 检查是否检测到特定标签
     */
    fun hasObjectWithLabel(result: ObjectDetectionResult, label: String): Boolean {
        return result.objects.any { it.label.contains(label, ignoreCase = true) }
    }

    /**
     * 关闭检测器
     */
    fun close() {
        detector.close()
    }
}

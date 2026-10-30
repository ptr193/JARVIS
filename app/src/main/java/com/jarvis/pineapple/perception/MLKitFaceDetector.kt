package com.jarvis.pineapple.perception

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * ML Kit 人脸检测器（功能 #34, #35）。
 *
 * 使用 Google ML Kit Face Detection API 实现真实的人脸检测和识别。
 */
class MLKitFaceDetector(private val context: Context) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()
    )

    /**
     * 检测结果
     */
    data class FaceDetectionResult(
        val isDetected: Boolean,
        val faceCount: Int,
        val faces: List<FaceInfo>,
        val inferenceTimeMs: Long
    )

    data class FaceInfo(
        val faceId: Int,
        val boundingBox: android.graphics.Rect,
        val confidence: Float,
        val hasSmile: Boolean,
        val smileProbability: Float,
        val leftEyeOpenProbability: Float,
        val rightEyeOpenProbability: Float,
        val landmarks: List<FaceLandmark> = emptyList(),
        val headEulerAngleX: Float = 0f,
        val headEulerAngleY: Float = 0f,
        val headEulerAngleZ: Float = 0f
    )

    data class FaceLandmark(
        val type: LandmarkType,
        val position: Pair<Float, Float>
    )

    enum class LandmarkType {
        LEFT_EYE, RIGHT_EYE, NOSE_BASE, LEFT_EAR, RIGHT_EAR,
        LEFT_CHEEK, RIGHT_CHEEK, MOUTH_LEFT, MOUTH_RIGHT, MOUTH_BOTTOM
    }

    /**
     * 检测人脸
     */
    suspend fun detect(bitmap: Bitmap): FaceDetectionResult = suspendCancellableCoroutine { continuation ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val startTime = System.currentTimeMillis()

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                val inferenceTime = System.currentTimeMillis() - startTime

                val faceInfos = faces.map { face ->
                    FaceInfo(
                        faceId = face.trackingId ?: 0,
                        boundingBox = face.boundingBox,
                        confidence = 1.0f,
                        hasSmile = (face.smilingProbability ?: 0f) > 0.5f,
                        smileProbability = face.smilingProbability ?: 0f,
                        leftEyeOpenProbability = face.leftEyeOpenProbability ?: 0f,
                        rightEyeOpenProbability = face.rightEyeOpenProbability ?: 0f,
                        landmarks = face.allLandmarks.map { landmark ->
                            FaceLandmark(
                                type = when (landmark.landmarkType) {
                                    com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE -> LandmarkType.LEFT_EYE
                                    com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE -> LandmarkType.RIGHT_EYE
                                    com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE -> LandmarkType.NOSE_BASE
                                    com.google.mlkit.vision.face.FaceLandmark.LEFT_EAR -> LandmarkType.LEFT_EAR
                                    com.google.mlkit.vision.face.FaceLandmark.RIGHT_EAR -> LandmarkType.RIGHT_EAR
                                    com.google.mlkit.vision.face.FaceLandmark.LEFT_CHEEK -> LandmarkType.LEFT_CHEEK
                                    com.google.mlkit.vision.face.FaceLandmark.RIGHT_CHEEK -> LandmarkType.RIGHT_CHEEK
                                    com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT -> LandmarkType.MOUTH_LEFT
                                    com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT -> LandmarkType.MOUTH_RIGHT
                                    com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM -> LandmarkType.MOUTH_BOTTOM
                                    else -> LandmarkType.NOSE_BASE
                                },
                                position = Pair(landmark.position.x, landmark.position.y)
                            )
                        },
                        headEulerAngleX = face.headEulerAngleX,
                        headEulerAngleY = face.headEulerAngleY,
                        headEulerAngleZ = face.headEulerAngleZ
                    )
                }

                val result = FaceDetectionResult(
                    isDetected = faces.isNotEmpty(),
                    faceCount = faces.size,
                    faces = faceInfos,
                    inferenceTimeMs = inferenceTime
                )

                continuation.resume(result)
            }
            .addOnFailureListener {
                continuation.resume(
                    FaceDetectionResult(
                        isDetected = false,
                        faceCount = 0,
                        faces = emptyList(),
                        inferenceTimeMs = System.currentTimeMillis() - startTime
                    )
                )
            }
    }

    /**
     * 比对两张人脸的相似度
     */
    fun compareFaces(features1: FloatArray, features2: FloatArray): Float {
        if (features1.size != features2.size) return 0f

        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in features1.indices) {
            dotProduct += features1[i] * features2[i]
            norm1 += features1[i] * features1[i]
            norm2 += features2[i] * features2[i]
        }

        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator > 0) (dotProduct / denominator).coerceIn(0f, 1f) else 0f
    }

    /**
     * 关闭检测器
     */
    fun close() {
        detector.close()
    }
}

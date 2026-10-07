package com.jarvis.pineapple.perception

/**
 * 目标检测（ML Kit / YOLO）+ 威胁探测
 */
class ObjectDetector {

    data class DetectedObject(val label: String, val confidence: Float, val boundingBox: RectF)

    data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** 多目标标记 */
    suspend fun detect(frame: Any): List<DetectedObject> {
        // 预留：接入 ML Kit Object Detection / YOLO
        return emptyList()
    }

    /** 威胁探测：陌生人/危险物品 */
    suspend fun detectThreat(objects: List<DetectedObject>): ThreatLevel {
        val dangerous = objects.any { it.label in DANGEROUS_LABELS }
        return if (dangerous) ThreatLevel.HIGH else ThreatLevel.LOW
    }

    enum class ThreatLevel { LOW, MEDIUM, HIGH }

    companion object {
        private val DANGEROUS_LABELS = setOf("knife", "gun", "weapon", "fire", "smoke")
    }
}

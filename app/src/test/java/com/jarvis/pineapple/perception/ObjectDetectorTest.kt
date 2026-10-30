package com.jarvis.pineapple.perception

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 单元测试：ObjectDetector 威胁等级判定（纯函数部分）。
 * ML Kit 推理需要 Android 设备，detect() 由集成测试覆盖。
 */
class ObjectDetectorTest {

    private fun obj(label: String) = ObjectDetector.DetectedObject(
        label = label,
        confidence = 0.9f,
        boundingBox = ObjectDetector.RectF(0f, 0f, 1f, 1f)
    )

    @Test
    fun `detectThreat returns HIGH for dangerous objects`() {
        assertEquals(
            ObjectDetector.ThreatLevel.HIGH,
            ObjectDetector.classifyThreat(listOf(obj("cup"), obj("knife")))
        )
    }

    @Test
    fun `detectThreat returns MEDIUM for person`() {
        assertEquals(
            ObjectDetector.ThreatLevel.MEDIUM,
            ObjectDetector.classifyThreat(listOf(obj("person")))
        )
    }

    @Test
    fun `detectThreat returns LOW for safe objects`() {
        assertEquals(
            ObjectDetector.ThreatLevel.LOW,
            ObjectDetector.classifyThreat(listOf(obj("cup"), obj("book")))
        )
    }

    @Test
    fun `detectThreat returns LOW for empty list`() {
        assertEquals(
            ObjectDetector.ThreatLevel.LOW,
            ObjectDetector.classifyThreat(emptyList())
        )
    }
}

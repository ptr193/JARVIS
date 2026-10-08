package com.jarvis.pineapple.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试：ObjectDetector 目标检测
 */
class ObjectDetectorTest {

    @Test
    fun `detect returns empty list when no model`() = kotlinx.coroutines.runBlocking {
        val detector = ObjectDetector()
        val result = detector.detect(frame = Any())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `detectThreat returns HIGH for dangerous objects`() = kotlinx.coroutines.runBlocking {
        val detector = ObjectDetector()
        val objects = listOf(
            ObjectDetector.DetectedObject(
                label = "knife",
                confidence = 0.9f,
                boundingBox = ObjectDetector.RectF(0f, 0f, 1f, 1f)
            )
        )
        val threat = detector.detectThreat(objects)
        assertEquals(ObjectDetector.ThreatLevel.HIGH, threat)
    }

    @Test
    fun `detectThreat returns LOW for safe objects`() = kotlinx.coroutines.runBlocking {
        val detector = ObjectDetector()
        val objects = listOf(
            ObjectDetector.DetectedObject(
                label = "cup",
                confidence = 0.95f,
                boundingBox = ObjectDetector.RectF(0f, 0f, 1f, 1f)
            )
        )
        val threat = detector.detectThreat(objects)
        assertEquals(ObjectDetector.ThreatLevel.LOW, threat)
    }
}

package com.jarvis.pineapple.wake

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeWakeDetectorTest {

    @Test
    fun startReturnsSensorRegistrationResultAndReportsFailure() {
        val start = readSource().substringAfter("fun start(): Boolean")
            .substringBefore("fun stop()")

        assertTrue(start.contains("val registered = try"))
        assertTrue(start.contains("sensorManager.registerListener(this, accelerometer"))
        assertTrue(start.contains("if (registered) null else REGISTRATION_FAILED"))
        assertTrue(start.contains("return registered"))
        assertFalse(start.contains("registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)\n        return true"))
    }

    private fun readSource(): String {
        val relativePath = "app/src/main/java/com/jarvis/pineapple/wake/ShakeWakeDetector.kt"
        return listOf(File(relativePath), File("../$relativePath"))
            .firstOrNull { it.exists() }?.readText()
            ?: error("无法定位源文件: $relativePath")
    }
}

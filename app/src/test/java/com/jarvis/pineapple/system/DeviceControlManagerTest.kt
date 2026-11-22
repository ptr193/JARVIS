package com.jarvis.pineapple.system

import com.jarvis.pineapple.system.DeviceControlManager.CameraInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControlManagerTest {

    @Test
    fun selectTorchCameraPicksFirstWithFlash() {
        val cameras = listOf(
            CameraInfo("0", false),
            CameraInfo("1", true),
            CameraInfo("2", true)
        )
        assertEquals("1", DeviceControlManager.selectTorchCamera(cameras))
    }

    @Test
    fun selectTorchCameraReturnsNullWhenNoFlash() {
        val cameras = listOf(CameraInfo("0", false), CameraInfo("1", false))
        assertNull(DeviceControlManager.selectTorchCamera(cameras))
        assertNull(DeviceControlManager.selectTorchCamera(emptyList()))
    }

    @Test
    fun vibrationDurationValidation() {
        assertTrue(DeviceControlManager.isVibrationDurationValid(1))
        assertTrue(DeviceControlManager.isVibrationDurationValid(200))
        assertTrue(DeviceControlManager.isVibrationDurationValid(60_000))
        assertFalse(DeviceControlManager.isVibrationDurationValid(0))
        assertFalse(DeviceControlManager.isVibrationDurationValid(-1))
        assertFalse(DeviceControlManager.isVibrationDurationValid(60_001))
    }
}

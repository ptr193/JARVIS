package com.jarvis.pineapple.system

import com.jarvis.pineapple.system.UsbDeviceManager.UsbDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDeviceManagerTest {

    @Test
    fun summarizeEmptyReportsNoDevice() {
        val text = UsbDeviceManager.summarize(emptyList())
        assertTrue(text.contains("未检测到 USB 设备"))
    }

    @Test
    fun summarizeListsDevicesWithHexIds() {
        val devices = listOf(
            UsbDeviceInfo("/dev/bus/usb/001/002", 0x18d1, 0x4ee1, 0, hasPermission = true)
        )
        val text = UsbDeviceManager.summarize(devices)
        assertTrue(text.contains("检测到 1 个 USB 设备"))
        assertTrue(text.contains("/dev/bus/usb/001/002"))
        assertTrue(text.contains("0x18d1"))
        assertTrue(text.contains("0x4ee1"))
        assertFalse(text.contains("无通信权限"))
    }

    @Test
    fun summarizeMarksMissingPermission() {
        val devices = listOf(
            UsbDeviceInfo("/dev/bus/usb/002/003", 0x05ac, 0x12a8, 239, hasPermission = false)
        )
        val text = UsbDeviceManager.summarize(devices)
        assertTrue(text.contains("无通信权限"))
    }
}

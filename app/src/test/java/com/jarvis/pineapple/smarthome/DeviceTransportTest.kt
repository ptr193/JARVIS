package com.jarvis.pineapple.smarthome

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 传输层验收：未配置网关 / 空扫描不得伪造成功。
 */
class DeviceTransportTest {

    @Test
    fun `empty gateway scan is 未配置网关`() {
        assertEquals("未配置网关", DeviceTransport.emptyScanMessage(configured = false, deviceCount = 0))
    }

    @Test
    fun `configured empty scan is 未检测到设备`() {
        assertEquals("未检测到设备", DeviceTransport.emptyScanMessage(configured = true, deviceCount = 0))
    }

    @Test
    fun `control without gateway is 未配置网关`() {
        assertEquals(
            "未配置网关",
            DeviceTransport.controlResult(configured = false, deviceKnown = false, delivered = false)
        )
    }

    @Test
    fun `control without device is 未检测到设备`() {
        assertEquals(
            "未检测到设备",
            DeviceTransport.controlResult(configured = true, deviceKnown = false, delivered = false)
        )
    }

    @Test
    fun `delivered command is 已下发`() {
        assertEquals(
            "已下发",
            DeviceTransport.controlResult(configured = true, deviceKnown = true, delivered = true)
        )
    }
}

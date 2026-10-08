package com.jarvis.pineapple.smarthome

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试：SmartHomeAdapter 智能家居适配层
 */
class SmartHomeAdapterTest {

    @Test
    fun `addDevice appends to list`() {
        val adapter = SmartHomeAdapter()
        assertTrue(adapter.devices.value.isEmpty())
        adapter.addDevice(TestDevice("d1", "灯"))
        assertEquals(1, adapter.devices.value.size)
        assertEquals("d1", adapter.devices.value.first().id)
    }

    @Test
    fun `removeDevice removes by id`() {
        val adapter = SmartHomeAdapter()
        adapter.addDevice(TestDevice("d1", "灯"))
        adapter.addDevice(TestDevice("d2", "窗帘"))
        adapter.removeDevice("d1")
        assertEquals(1, adapter.devices.value.size)
        assertEquals("d2", adapter.devices.value.first().id)
    }

    @Test
    fun `groupControl invokes action for group members`() {
        val adapter = SmartHomeAdapter()
        val d1 = TestDevice("d1", "灯1")
        val d2 = TestDevice("d2", "灯2")
        val d3 = TestDevice("d3", "灯3")
        adapter.addDevice(d1)
        adapter.addDevice(d2)
        adapter.addDevice(d3)
        adapter.createGroup("all", listOf("d1", "d2"))
        val toggled = mutableListOf<String>()
        runBlocking {
            adapter.groupControl("all") { toggled.add(it.id) }
        }
        assertEquals(setOf("d1", "d2"), toggled.toSet())
    }

    @Test
    fun `registerScene and triggerScene fire action`() {
        val adapter = SmartHomeAdapter()
        var fired = false
        adapter.registerScene("away") { fired = true }
        adapter.triggerScene("away")
        assertTrue(fired)
    }

    private class TestDevice(
        override val id: String,
        override val name: String,
        override val type: DeviceType = DeviceType.LIGHT,
        override var isOnline: Boolean = true
    ) : SmartDevice {
        var on = false
        override suspend fun turnOn() { on = true }
        override suspend fun turnOff() { on = false }
        override suspend fun getState(): Map<String, Any> = mapOf("on" to on)
    }
}

package com.jarvis.pineapple.smarthome

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartHomeManagerTest {

    @Test
    fun `failed commands do not change local device state`() = runBlocking {
        val transport = FakeTransport(delivered = false)
        val manager = SmartHomeManager(transport) { true }
        val original = SmartHomeManager.DeviceState(
            isOn = true,
            brightness = 37,
            colorTemperature = 4100,
            targetTemperature = 21f,
            position = 42,
            isLocked = false
        )
        manager.addDevice(device(state = original))

        assertEquals("指令未送达", manager.turnOffLight("light"))
        assertEquals("指令未送达", manager.setLightBrightness("light", 90))
        assertEquals("指令未送达", manager.setLightColorTemperature("light", 5000))
        assertEquals("指令未送达", manager.setTargetTemperature("light", 26f))
        assertEquals("指令未送达", manager.openCurtain("light"))
        assertEquals("指令未送达", manager.lock("light"))
        assertEquals(
            "指令未送达",
            manager.controlCamera("light", SmartHomeManager.CameraAction.STOP_STREAMING)
        )
        assertFalse(manager.pairDevice("light", "1234"))

        assertEquals(original, manager.getDevice("light")?.state)
        assertFalse(manager.getDevice("light")?.isConnected == true)
    }

    @Test
    fun `delivered commands update state and retain parameters`() = runBlocking {
        val transport = FakeTransport(delivered = true)
        val manager = SmartHomeManager(transport) { true }
        manager.addDevice(device())

        assertEquals("已下发", manager.setLightBrightness("light", 140))
        assertEquals("已下发", manager.setLightColorTemperature("light", 1000))
        assertEquals("已下发", manager.openCurtain("light"))
        assertEquals("已下发", manager.unlock("light"))
        assertEquals(
            "已下发",
            manager.controlCamera("light", SmartHomeManager.CameraAction.START_RECORDING)
        )
        assertTrue(manager.pairDevice("light", "1234"))

        val updated = manager.getDevice("light")!!
        assertEquals(100, updated.state.brightness)
        assertEquals(2700, updated.state.colorTemperature)
        assertEquals(100, updated.state.position)
        assertFalse(updated.state.isLocked)
        assertTrue(updated.state.isOn)
        assertTrue(updated.isConnected)
        assertEquals(100, transport.commands.first { it.command == "brightness" }.params["value"])
        assertEquals("1234", transport.commands.first { it.command == "pair" }.params["pairingCode"])
    }

    @Test
    fun `scene delays sequentially and reports notification failure`() = runBlocking {
        val transport = FakeTransport(delivered = true)
        val notifications = mutableListOf<String>()
        val manager = SmartHomeManager(transport) { message ->
            notifications += message
            true
        }
        manager.addDevice(device())
        val scene = manager.createScene(
            "evening",
            "",
            listOf(
                SmartHomeManager.SceneAction.DeviceAction(
                    "light",
                    SmartHomeManager.DeviceControlAction.TurnOn
                ),
                SmartHomeManager.SceneAction.DelayAction(40),
                SmartHomeManager.SceneAction.NotifyAction("done")
            )
        )

        val started = System.nanoTime()
        assertEquals("场景已执行", manager.activateScene(scene.id))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("delay action was skipped: ${elapsedMs}ms", elapsedMs >= 35)
        assertTrue(manager.getDevice("light")!!.state.isOn)
        assertEquals(listOf("done"), notifications)

        val failing = SmartHomeManager(transport) { false }
        failing.addDevice(device())
        val failedScene = failing.createScene(
            "failed",
            "",
            listOf(
                SmartHomeManager.SceneAction.DeviceAction(
                    "light",
                    SmartHomeManager.DeviceControlAction.TurnOn
                ),
                SmartHomeManager.SceneAction.DelayAction(-1),
                SmartHomeManager.SceneAction.NotifyAction("not posted")
            )
        )
        val result = failing.activateScene(failedScene.id)
        assertTrue(result.startsWith("场景执行失败"))
        assertTrue(result.contains("动作 2"))
        assertTrue(result.contains("动作 3"))
    }

    @Test
    fun `group control reports partial delivery`() = runBlocking {
        val transport = FakeTransport(delivered = true, failedDeviceIds = mutableSetOf("two"))
        val manager = SmartHomeManager(transport) { true }
        manager.addDevice(device(id = "one"))
        manager.addDevice(device(id = "two"))
        val group = manager.createGroup(
            "both",
            listOf("one", "two"),
            SmartHomeManager.GroupType.CUSTOM
        )

        val result = manager.controlGroup(group.id, SmartHomeManager.DeviceControlAction.TurnOn)

        assertTrue(result.startsWith("部分指令失败"))
        assertTrue(result.contains("two"))
        assertTrue(manager.getDevice("one")!!.state.isOn)
        assertFalse(manager.getDevice("two")!!.state.isOn)
    }

    private fun device(
        id: String = "light",
        state: SmartHomeManager.DeviceState = SmartHomeManager.DeviceState()
    ) = SmartHomeManager.SmartDevice(
        id = id,
        name = id,
        type = SmartHomeManager.DeviceType.LIGHT,
        protocol = SmartHomeManager.ProtocolType.WIFI,
        state = state
    )

    private data class SentCommand(
        val deviceId: String,
        val command: String,
        val params: Map<String, Any?>
    )

    private class FakeTransport(
        var configured: Boolean = true,
        private val delivered: Boolean,
        private val failedDeviceIds: Set<String> = emptySet()
    ) : SmartHomeTransport {
        val commands = mutableListOf<SentCommand>()

        override val isConfigured: Boolean get() = configured

        override suspend fun ssdpDiscover(timeoutMs: Long): List<DeviceTransport.DiscoveredDevice> =
            emptyList()

        override suspend fun fetchDevices(): List<DeviceTransport.DiscoveredDevice> = emptyList()

        override suspend fun sendCommand(
            deviceId: String,
            command: String,
            params: Map<String, Any?>
        ): Boolean {
            commands += SentCommand(deviceId, command, params)
            return delivered && deviceId !in failedDeviceIds
        }
    }
}

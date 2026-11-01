package com.jarvis.pineapple.security

import com.jarvis.pineapple.system.PermissionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试：PermissionController 权限分级
 */
class PermissionControllerTest {

    @Test
    fun `owner can perform all actions`() {
        val c = PermissionController()
        c.setLevel(PermissionLevel.OWNER)
        assertTrue(c.canPerform("wipe_data"))
        assertTrue(c.canPerform("make_call"))
        assertTrue(c.canPerform("anything"))
    }

    @Test
    fun `guest cannot perform restricted actions`() {
        val c = PermissionController()
        c.setLevel(PermissionLevel.GUEST)
        assertFalse(c.canPerform("wipe_data"))
        assertFalse(c.canPerform("change_personality"))
        assertFalse(c.canPerform("factory_reset"))
        assertTrue(c.canPerform("make_call"))
    }

    @Test
    fun `temporary can only perform allowed actions`() {
        val c = PermissionController()
        c.setLevel(PermissionLevel.TEMPORARY)
        assertTrue(c.canPerform("make_call"))
        assertTrue(c.canPerform("send_sms"))
        assertFalse(c.canPerform("wipe_data"))
        assertFalse(c.canPerform("anything"))
    }

    @Test
    fun `default level is owner`() {
        val c = PermissionController()
        assertEquals(PermissionLevel.OWNER, c.getLevel())
    }

    @Test
    fun `permission gate denies without fake success`() {
        assertFalse(PermissionGate.phoneCallAllowed(false))
        assertFalse(PermissionGate.smsSendAllowed(false))
        assertFalse(PermissionGate.calendarWriteAllowed(false))
        assertFalse(PermissionGate.wifiChangeAllowed(false))
        assertFalse(PermissionGate.bluetoothChangeAllowed(false))
        assertFalse(PermissionGate.brightnessWriteAllowed(false))
        assertEquals("缺少 CALL_PHONE 权限", PermissionGate.deniedMessage("CALL_PHONE"))
        assertEquals("缺少 SEND_SMS 权限", PermissionGate.allowOrDenied(false, "SEND_SMS"))
        assertNull(PermissionGate.allowOrDenied(true, "CALL_PHONE"))
        assertTrue(PermissionGate.phoneCallAllowed(true))
    }

    @Test
    fun `lock and wipe refuse when device admin inactive`() {
        assertFalse(SecurityManager.lockResult(false))
        assertFalse(SecurityManager.wipeResult(false))
        assertTrue(SecurityManager.lockResult(true))
        assertTrue(SecurityManager.wipeResult(true))
        assertEquals("设备管理器未激活", SecurityManager.ADMIN_INACTIVE)
        assertEquals(
            "android.app.action.ADD_DEVICE_ADMIN",
            SecurityManager.ADMIN_ACTIVATION_ACTION
        )
    }
}

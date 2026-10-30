package com.jarvis.pineapple.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}

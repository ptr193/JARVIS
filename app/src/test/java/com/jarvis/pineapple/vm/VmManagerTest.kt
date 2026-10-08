package com.jarvis.pineapple.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试：VmManager 虚拟机管理
 */
class VmManagerTest {

    @Test
    fun `create adds vm to list`() {
        val mgr = VmManager()
        assertTrue(mgr.vms.value.isEmpty())
        val vm = mgr.create("ubuntu-1", "Ubuntu 24.04")
        assertEquals(1, mgr.vms.value.size)
        assertEquals("ubuntu-1", vm.name)
        assertEquals("Ubuntu 24.04", vm.distro)
        assertEquals(VmState.STOPPED, vm.state)
        assertTrue(vm.id.startsWith("vm_"))
    }

    @Test
    fun `start and stop transitions`() {
        val mgr = VmManager()
        val vm = mgr.create("v", "Debian 12")
        mgr.start(vm.id)
        assertEquals(VmState.RUNNING, mgr.vms.value.first().state)
        mgr.stop(vm.id)
        assertEquals(VmState.STOPPED, mgr.vms.value.first().state)
    }

    @Test
    fun `delete removes vm`() {
        val mgr = VmManager()
        val vm = mgr.create("v", "Fedora")
        mgr.delete(vm.id)
        assertTrue(mgr.vms.value.isEmpty())
    }

    @Test
    fun `multiple vms run independently`() {
        val mgr = VmManager()
        val a = mgr.create("a", "Alpine")
        val b = mgr.create("b", "Arch")
        mgr.start(a.id)
        assertEquals(2, mgr.vms.value.size)
        val aState = mgr.vms.value.first { it.id == a.id }.state
        val bState = mgr.vms.value.first { it.id == b.id }.state
        assertEquals(VmState.RUNNING, aState)
        assertEquals(VmState.STOPPED, bState)
    }

    @Test
    fun `createChannel does not throw`() {
        val mgr = VmManager()
        val a = mgr.create("a", "Alpine")
        val b = mgr.create("b", "Arch")
        mgr.createChannel(a.id, b.id)
    }
}

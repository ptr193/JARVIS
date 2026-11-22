package com.jarvis.pineapple.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 纯 JVM 测试：验证对话引擎选择虚拟机的纯函数逻辑。
 */
class VmResolutionTest {

    private val ubuntu = VmSummary(id = "1", name = "Ubuntu", running = false)
    private val debian = VmSummary(id = "2", name = "Debian", running = true)

    @Test
    fun emptyListReturnsNull() {
        assertNull(ChatEngine.resolveVm(emptyList(), null))
        assertNull(ChatEngine.resolveVm(emptyList(), "Ubuntu"))
    }

    @Test
    fun prefersRunningVmWhenNameMissing() {
        assertEquals("2", ChatEngine.resolveVm(listOf(ubuntu, debian), null)?.id)
    }

    @Test
    fun fallsBackToFirstWhenNoneRunning() {
        assertEquals("1", ChatEngine.resolveVm(listOf(ubuntu), null)?.id)
    }

    @Test
    fun matchesNameCaseInsensitively() {
        assertEquals("1", ChatEngine.resolveVm(listOf(ubuntu, debian), "ubuntu")?.id)
    }

    @Test
    fun fallsBackToContainsMatch() {
        val vm = VmSummary(id = "3", name = "Ubuntu 24.04", running = false)
        assertEquals("3", ChatEngine.resolveVm(listOf(vm), "24.04")?.id)
    }

    @Test
    fun unknownNameReturnsNull() {
        assertNull(ChatEngine.resolveVm(listOf(ubuntu, debian), "Fedora"))
    }
}

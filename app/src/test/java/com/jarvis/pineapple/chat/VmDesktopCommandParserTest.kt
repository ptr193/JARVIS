package com.jarvis.pineapple.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 虚拟机桌面/浏览器指令解析（纯函数）测试。
 */
class VmDesktopCommandParserTest {

    @Test
    fun detectsDesktopRequestWithoutVmName() {
        val request = VmDesktopCommandParser.parse("打开虚拟机桌面")

        assertEquals(VmDesktopAction.DESKTOP, request?.action)
        assertNull(request?.vmName)
    }

    @Test
    fun detectsBrowserRequestWithVmName() {
        val request = VmDesktopCommandParser.parse("在虚拟机 Ubuntu 打开浏览器")

        assertEquals(VmDesktopAction.BROWSER, request?.action)
        assertEquals("Ubuntu", request?.vmName)
    }

    @Test
    fun detectsBrowserRequestWithoutVmName() {
        val request = VmDesktopCommandParser.parse("帮我打开虚拟机的浏览器")

        assertEquals(VmDesktopAction.BROWSER, request?.action)
        assertNull(request?.vmName)
    }

    @Test
    fun explicitExecutionCommandIsNotHijacked() {
        assertNull(VmDesktopCommandParser.parse("虚拟机 Ubuntu 执行 ls -la"))
        assertNull(VmDesktopCommandParser.parse("终端执行 uname -a"))
    }

    @Test
    fun ordinaryChatIsIgnored() {
        assertNull(VmDesktopCommandParser.parse("你好，介绍一下你自己"))
        assertNull(VmDesktopCommandParser.parse("桌面壁纸怎么换"))
    }
}

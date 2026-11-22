package com.jarvis.pineapple.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 宿主机 root 指令解析（纯函数）测试。 */
class RootCommandParserTest {

    @Test
    fun parsesRootExecute() {
        assertEquals("id", RootCommandParser.parse("root 执行 id"))
    }

    @Test
    fun parsesRootWithPrivilegePhrase() {
        assertEquals(
            "getprop ro.build.version.release",
            RootCommandParser.parse("以root权限执行 getprop ro.build.version.release")
        )
    }

    @Test
    fun parsesHostExecute() {
        assertEquals("ls /system", RootCommandParser.parse("宿主机执行 ls /system"))
    }

    @Test
    fun parsesSuColon() {
        assertEquals("id", RootCommandParser.parse("su: id"))
    }

    @Test
    fun ignoresOrdinaryConversation() {
        assertNull(RootCommandParser.parse("root权限是什么"))
        assertNull(RootCommandParser.parse("你好"))
        assertNull(RootCommandParser.parse("虚拟机 Ubuntu 执行 ls"))
    }
}

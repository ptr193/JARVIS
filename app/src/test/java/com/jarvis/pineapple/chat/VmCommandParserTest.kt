package com.jarvis.pineapple.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 纯 JVM 测试：只验证“AI 操控虚拟机”指令的解析规则，不接触 Android 框架。
 */
class VmCommandParserTest {

    @Test
    fun parsesVmNameAndCommand() {
        val parsed = VmCommandParser.parse("虚拟机 Ubuntu 执行 ls -la")
        assertEquals("Ubuntu", parsed?.vmName)
        assertEquals("ls -la", parsed?.command)
    }

    @Test
    fun parsesInVmPrefix() {
        val parsed = VmCommandParser.parse("在虚拟机 Debian 执行 uname -a")
        assertEquals("Debian", parsed?.vmName)
        assertEquals("uname -a", parsed?.command)
    }

    @Test
    fun parsesWithoutVmName() {
        val parsed = VmCommandParser.parse("虚拟机执行 whoami")
        assertNull(parsed?.vmName)
        assertEquals("whoami", parsed?.command)
    }

    @Test
    fun parsesTerminalPrefix() {
        val parsed = VmCommandParser.parse("终端执行 pwd")
        assertNull(parsed?.vmName)
        assertEquals("pwd", parsed?.command)
    }

    @Test
    fun parsesVmColonPrefix() {
        val parsed = VmCommandParser.parse("vm: echo hello")
        assertNull(parsed?.vmName)
        assertEquals("echo hello", parsed?.command)
    }

    @Test
    fun ignoresPlainConversation() {
        assertNull(VmCommandParser.parse("虚拟机是什么？"))
        assertNull(VmCommandParser.parse("今天天气怎么样"))
        assertNull(VmCommandParser.parse(""))
    }

    @Test
    fun rejectsBlankCommand() {
        assertNull(VmCommandParser.parse("虚拟机 Ubuntu 执行   "))
        assertNull(VmCommandParser.parse("终端执行   "))
    }

    @Test
    fun rejectsOverlongMessage() {
        val long = "虚拟机执行 " + "a".repeat(600)
        assertNull(VmCommandParser.parse(long))
    }
}

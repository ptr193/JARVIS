package com.jarvis.pineapple.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCommandParserTest {

    @Test
    fun parseVolumeBrightnessAndAppsWithoutFakingUnknownApps() {
        val volume = AssistantCommandParser.parse("音量 80")
        assertEquals("SET_VOLUME", volume.action)
        assertTrue(volume.isExecutable)
        assertEquals("80", volume.parameters["level"])
        assertEquals(12, AssistantCommandParser.volumeFromPercent(80))

        val brightness = AssistantCommandParser.parse("亮度 40")
        assertEquals("SET_BRIGHTNESS", brightness.action)
        assertEquals(102, AssistantCommandParser.brightnessFromPercent(40))

        val wechat = AssistantCommandParser.parse("打开微信")
        assertEquals("OPEN_APP", wechat.action)
        assertEquals("com.tencent.mm", wechat.parameters["package"])
        assertTrue(wechat.isExecutable)

        val unknownApp = AssistantCommandParser.parse("打开不存在的星球应用")
        assertEquals("OPEN_APP", unknownApp.action)
        assertFalse(unknownApp.isExecutable)
        assertTrue(unknownApp.displayLabel.contains("无法识别应用"))
    }

    @Test
    fun parseCallSmsReminderNavigateAndToggles() {
        val call = AssistantCommandParser.parse("打电话 13800138000")
        assertEquals("MAKE_CALL", call.action)
        assertEquals("13800138000", call.parameters["number"])
        assertTrue(call.isExecutable)

        val missingNumber = AssistantCommandParser.parse("打电话")
        assertFalse(missingNumber.isExecutable)
        assertEquals("未提供电话号码", missingNumber.displayLabel)

        val sms = AssistantCommandParser.parse("发短信 13900001111 内容你好")
        assertEquals("SEND_SMS", sms.action)
        assertEquals("13900001111", sms.parameters["number"])
        assertEquals("你好", sms.parameters["body"])

        val reminder = AssistantCommandParser.parse("提醒我喝水")
        assertEquals("ADD_REMINDER", reminder.action)
        assertTrue(reminder.isExecutable)
        assertEquals((5 * 60_000L).toString(), reminder.parameters["delayMs"])

        val nav = AssistantCommandParser.parse("导航回家")
        assertEquals("NAVIGATE", nav.action)
        assertEquals("回家", nav.parameters["destination"])

        val missingDest = AssistantCommandParser.parse("导航")
        assertFalse(missingDest.isExecutable)
        assertEquals("未提供目的地", missingDest.displayLabel)

        val wifiOff = AssistantCommandParser.parse("关闭 wifi")
        assertEquals("SET_WIFI", wifiOff.action)
        assertEquals("false", wifiOff.parameters["enabled"])

        val btOn = AssistantCommandParser.parse("打开蓝牙")
        assertEquals("SET_BLUETOOTH", btOn.action)
        assertEquals("true", btOn.parameters["enabled"])
        assertEquals("UNKNOWN", AssistantCommandParser.parse("今天天气怎么样").action)
        assertTrue(AssistantCommandParser.isDirectCommand("打开微信"))
        assertTrue(AssistantCommandParser.isDirectCommand("音量 50"))
        assertFalse(AssistantCommandParser.isDirectCommand("我想打开窗户透透气，顺便聊聊今天天气"))
        assertFalse(AssistantCommandParser.isDirectCommand("今天天气怎么样"))
    }

    @Test
    fun chatShortcutsCoverCoreActions() {
        assertEquals(6, AssistantCommandParser.CHAT_SHORTCUTS.size)
        AssistantCommandParser.CHAT_SHORTCUTS.forEach { (command, _) ->
            val parsed = AssistantCommandParser.parse(command)
            assertTrue("$command 应可解析", parsed.action != "UNKNOWN")
        }
    }

    @Test
    fun chatEngineAndEnhancerWireParserWithoutFakeSuccess() {
        val enhancer = readSource("app/src/main/java/com/jarvis/pineapple/chat/ConversationEnhancer.kt")
        assertTrue(enhancer.contains("AssistantCommandParser.parse(message)"))
        val engine = readSource("app/src/main/java/com/jarvis/pineapple/chat/ChatEngine.kt")
        assertTrue(engine.contains("isDirectCommand"))
        assertTrue(engine.contains("commandExecutor"))
        assertTrue(engine.contains("无法执行该指令：系统控制器不可用"))
        val screen = readSource("app/src/main/java/com/jarvis/pineapple/ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("AssistantCommandParser.CHAT_SHORTCUTS"))
        val executor = readSource("app/src/main/java/com/jarvis/pineapple/chat/SimpleCommandExecutor.kt")
        assertTrue(executor.contains("打开失败：未安装"))
        assertTrue(executor.contains("拨打失败：未授予电话权限"))
        assertFalse(executor.contains("已成功") && executor.contains("return true"))
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(File(relativePath), File("../$relativePath"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("无法定位源文件: $relativePath")
        return file.readText()
    }
}

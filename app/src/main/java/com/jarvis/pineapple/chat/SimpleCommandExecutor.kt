package com.jarvis.pineapple.chat

import com.jarvis.pineapple.system.CommunicationManager
import com.jarvis.pineapple.system.MediaController
import com.jarvis.pineapple.system.NavigationController
import com.jarvis.pineapple.system.PhoneController
import com.jarvis.pineapple.system.SmsController
import com.jarvis.pineapple.system.SystemSettingsController
import java.util.UUID

/**
 * 将解析后的极简指令交给系统控制器执行。失败返回中文原因，禁止假成功。
 */
class SimpleCommandExecutor(
    private val mediaController: MediaController,
    private val phoneController: PhoneController,
    private val smsController: SmsController,
    private val settingsController: SystemSettingsController,
    private val communicationManager: CommunicationManager,
    private val navigationController: NavigationController
) {

    fun execute(command: AssistantCommandParser.ParsedCommand): String {
        if (!command.isExecutable) {
            return command.displayLabel.ifBlank { "无法执行该指令" }
        }
        return try {
            when (command.action) {
                "SET_VOLUME" -> setVolume(command)
                "SET_BRIGHTNESS" -> setBrightness(command)
                "OPEN_APP" -> openApp(command)
                "MAKE_CALL" -> makeCall(command)
                "SEND_SMS" -> sendSms(command)
                "ADD_REMINDER" -> addReminder(command)
                "NAVIGATE" -> navigate(command)
                "SET_WIFI" -> setWifi(command)
                "SET_BLUETOOTH" -> setBluetooth(command)
                else -> "无法执行该指令"
            }
        } catch (e: Exception) {
            "执行失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun setVolume(command: AssistantCommandParser.ParsedCommand): String {
        val level = command.parameters["level"]?.toIntOrNull() ?: return "未提供音量数值"
        val stream = AssistantCommandParser.volumeFromPercent(level)
        mediaController.setVolume(stream)
        return "已将音量调至 $level%"
    }

    private fun setBrightness(command: AssistantCommandParser.ParsedCommand): String {
        val level = command.parameters["level"]?.toIntOrNull() ?: return "未提供亮度数值"
        val value = AssistantCommandParser.brightnessFromPercent(level)
        return if (mediaController.setBrightness(value)) {
            "已将亮度调至 $level%"
        } else {
            "调节亮度失败：未授予修改系统设置权限"
        }
    }

    private fun openApp(command: AssistantCommandParser.ParsedCommand): String {
        val pkg = command.parameters["package"] ?: return "无法识别应用"
        val app = command.parameters["app"] ?: pkg
        return if (settingsController.openApp(pkg)) {
            "已打开$app"
        } else {
            "打开失败：未安装$app"
        }
    }

    private fun makeCall(command: AssistantCommandParser.ParsedCommand): String {
        val number = command.parameters["number"] ?: return "未提供电话号码"
        return if (phoneController.call(number)) {
            "正在拨打 $number"
        } else {
            "拨打失败：未授予电话权限"
        }
    }

    private fun sendSms(command: AssistantCommandParser.ParsedCommand): String {
        val number = command.parameters["number"] ?: return "未提供短信号码"
        val body = command.parameters["body"] ?: return "未提供短信内容"
        return if (smsController.send(number, body)) {
            "已向 $number 发送短信"
        } else {
            "发送短信失败：未授予短信权限"
        }
    }

    private fun addReminder(command: AssistantCommandParser.ParsedCommand): String {
        val title = command.parameters["title"] ?: return "未提供提醒内容"
        val delay = command.parameters["delayMs"]?.toLongOrNull() ?: 5 * 60_000L
        communicationManager.addReminder(
            CommunicationManager.Reminder(
                id = UUID.randomUUID().toString(),
                title = title,
                description = title,
                triggerTime = System.currentTimeMillis() + delay
            )
        )
        return "已添加提醒：$title"
    }

    private fun navigate(command: AssistantCommandParser.ParsedCommand): String {
        val dest = command.parameters["destination"] ?: return "未提供目的地"
        navigationController.navigateTo(dest)
        return "正在导航到$dest"
    }

    private fun setWifi(command: AssistantCommandParser.ParsedCommand): String {
        val enabled = command.parameters["enabled"]?.toBoolean() ?: true
        return if (settingsController.setWifiEnabled(enabled)) {
            if (enabled) "已打开 Wi-Fi" else "已关闭 Wi-Fi"
        } else {
            "切换 Wi-Fi 失败：未授予无线网络权限"
        }
    }

    private fun setBluetooth(command: AssistantCommandParser.ParsedCommand): String {
        val enabled = command.parameters["enabled"]?.toBoolean() ?: true
        return if (settingsController.setBluetoothEnabled(enabled)) {
            if (enabled) "已打开蓝牙" else "已关闭蓝牙"
        } else {
            "切换蓝牙失败：未授予蓝牙权限"
        }
    }
}

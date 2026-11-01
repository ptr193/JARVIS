package com.jarvis.pineapple.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.pineapple.system.CalendarController
import com.jarvis.pineapple.system.CommunicationManager
import com.jarvis.pineapple.system.CrossAppOperator
import com.jarvis.pineapple.system.DeviceControlManager
import com.jarvis.pineapple.system.HardwareDiagnostics
import com.jarvis.pineapple.system.MediaController
import com.jarvis.pineapple.system.NavigationController
import com.jarvis.pineapple.system.PhoneController
import com.jarvis.pineapple.system.RemoteAssistanceManager
import com.jarvis.pineapple.system.SmsController
import com.jarvis.pineapple.system.SystemSettingsController
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * 系统操控：把电话 / 短信 / 日程 / 导航 / 系统设置 / 媒体 / 跨应用 /
 * 硬件诊断 / 远程协助等已实现但此前没有入口的系统控制器接入 UI（REQ-05）。
 */
@Composable
fun SystemControlTab(showMsg: (String) -> Unit) {
    val scope = rememberCoroutineScope()

    val phone = remember { GlobalContext.get().get<PhoneController>() }
    val sms = remember { GlobalContext.get().get<SmsController>() }
    val calendar = remember { GlobalContext.get().get<CalendarController>() }
    val navigation = remember { GlobalContext.get().get<NavigationController>() }
    val settings = remember { GlobalContext.get().get<SystemSettingsController>() }
    val media = remember { GlobalContext.get().get<MediaController>() }
    val crossApp = remember { GlobalContext.get().get<CrossAppOperator>() }
    val communication = remember { GlobalContext.get().get<CommunicationManager>() }
    val deviceControl = remember { GlobalContext.get().get<DeviceControlManager>() }
    val diagnostics = remember { GlobalContext.get().get<HardwareDiagnostics>() }
    val remote = remember { GlobalContext.get().get<RemoteAssistanceManager>() }

    var phoneNumber by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var appPackage by remember { mutableStateOf("") }

    var phoneInfo by remember { mutableStateOf("未操作") }
    var systemInfo by remember { mutableStateOf("未操作") }
    var diagInfo by remember { mutableStateOf("尚未诊断") }
    var remoteInfo by remember { mutableStateOf("无远程会话") }
    var scheduleInfo by remember { mutableStateOf("暂无日程") }

    Text("系统操控", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    SectionCard("电话与短信 (#49-#52)") {
        OutlinedTextField(
            value = phoneNumber, onValueChange = { phoneNumber = it },
            label = { Text("号码") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = message, onValueChange = { message = it },
            label = { Text("短信内容") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Text(phoneInfo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            Button(onClick = {
                val ok = phone.call(phoneNumber.trim())
                phoneInfo = if (ok) "已发起通话" else "拨号失败（缺少 CALL_PHONE 权限）"
                showMsg(phoneInfo)
            }) { Text("拨打电话") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val ok = sms.send(phoneNumber.trim(), message)
                phoneInfo = if (ok) "短信已发送" else "发送失败（缺少 SEND_SMS 权限）"
                showMsg(phoneInfo)
            }) { Text("发送短信") }
        }
        Row {
            OutlinedButton(onClick = {
                val logs = phone.readCallLogs()
                phoneInfo = if (logs.isEmpty()) "无通话记录或缺少权限" else "最近通话：\n" + logs.take(5).joinToString("\n")
            }) { Text("读取通话记录") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val inbox = sms.readInbox()
                phoneInfo = if (inbox.isEmpty()) "无短信或缺少权限" else "最近短信：\n" + inbox.take(5).joinToString("\n")
            }) { Text("读取短信") }
        }
    }

    SectionCard("日程与导航 (#53-#56)") {
        OutlinedTextField(
            value = destination, onValueChange = { destination = it },
            label = { Text("目的地 / 日程标题") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Text(scheduleInfo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            Button(onClick = {
                val now = System.currentTimeMillis()
                val ok = calendar.createEvent(
                    CalendarController.Event(
                        title = destination.ifBlank { "JARVIS 日程" },
                        startTime = now + 3_600_000,
                        endTime = now + 7_200_000,
                        description = "由 JARVIS 创建"
                    )
                )
                scheduleInfo = if (ok) "日程已写入系统日历" else "创建失败（缺少日历权限）"
                showMsg(scheduleInfo)
            }) { Text("创建日程") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                navigation.navigateTo(destination.ifBlank { "家" })
                scheduleInfo = "已发起导航：$destination"
                showMsg(scheduleInfo)
            }) { Text("发起导航") }
        }
        Text("提醒事项：${communication.reminders.value.size} 条 · 行程：${communication.getUpcomingTravels().size} 条", fontSize = 12.sp)
        Row {
            OutlinedButton(onClick = {
                communication.addReminder(
                    CommunicationManager.Reminder(
                        id = "rem_${System.currentTimeMillis()}",
                        title = destination.ifBlank { "JARVIS 提醒" },
                        description = "由系统操控页创建",
                        triggerTime = System.currentTimeMillis() + 600_000
                    )
                )
                scheduleInfo = "已添加提醒，共 ${communication.reminders.value.size} 条"
                showMsg(scheduleInfo)
            }) { Text("添加提醒") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                scheduleInfo = "今日行程：${communication.getTodayItinerary().size} 项"
                showMsg(scheduleInfo)
            }) { Text("查看今日行程") }
        }
    }

    SectionCard("系统设置与媒体 (#57-#60)") {
        Text(systemInfo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            Button(onClick = {
                systemInfo = if (settings.setWifiEnabled(true)) "已请求开启 WiFi" else "缺少 CHANGE_WIFI_STATE 权限"
                showMsg(systemInfo)
            }) { Text("开 WiFi") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                systemInfo = if (settings.setWifiEnabled(false)) "已请求关闭 WiFi" else "缺少 CHANGE_WIFI_STATE 权限"
                showMsg(systemInfo)
            }) { Text("关 WiFi") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                systemInfo = if (settings.setBluetoothEnabled(true)) "已请求开启蓝牙" else "缺少 BLUETOOTH 权限"
                showMsg(systemInfo)
            }) { Text("开蓝牙") }
        }
        Row {
            Button(onClick = { media.playPause(); systemInfo = "播放/暂停已切换"; showMsg(systemInfo) }) { Text("播放/暂停") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { media.next(); systemInfo = "已切到下一首"; showMsg(systemInfo) }) { Text("下一首") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { media.previous(); systemInfo = "已切到上一首"; showMsg(systemInfo) }) { Text("上一首") }
        }
        Row {
            Button(onClick = { media.volumeUp(); systemInfo = "音量：${media.getVolume()}"; showMsg(systemInfo) }) { Text("音量+") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { media.volumeDown(); systemInfo = "音量：${media.getVolume()}"; showMsg(systemInfo) }) { Text("音量-") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val f = settings.takeScreenshot()
                systemInfo = if (f != null) "截图：${f.absolutePath}" else "截图失败（缺少录屏授权）"
                showMsg(systemInfo)
            }) { Text("截图") }
        }
    }

    SectionCard("跨应用操作 (#61-#63)") {
        OutlinedTextField(
            value = appPackage, onValueChange = { appPackage = it },
            label = { Text("应用包名") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row {
            Button(onClick = {
                settings.openApp(appPackage.trim())
                systemInfo = "已请求打开 ${appPackage.trim()}"
                showMsg(systemInfo)
            }) { Text("打开应用") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                crossApp.addToWhitelist(appPackage.trim())
                systemInfo = "白名单：${crossApp.getWhitelist().joinToString("、")}"
                showMsg("已加入跨应用白名单")
            }) { Text("加入白名单") }
        }
        Text("智能家居灯具：${deviceControl.lightStates.value.size} 台", fontSize = 12.sp)
        Row {
            OutlinedButton(onClick = {
                val light = deviceControl.lightStates.value.firstOrNull()
                if (light == null) showMsg("未检测到设备")
                else {
                    scope.launch {
                        showMsg(deviceControl.turnOnLight(light.deviceId))
                    }
                }
            }) { Text("开灯") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { deviceControl.disarmSecurity(); showMsg("已解除安防布防") }) { Text("解除布防") }
        }
    }

    SectionCard("硬件诊断 (#64-#66)") {
        Text(diagInfo, fontSize = 12.sp)
        Row {
            Button(onClick = {
                scope.launch {
                    val issues = diagnostics.quickCheck()
                    diagInfo = if (issues.isEmpty()) "快速检查：未发现异常"
                    else "发现 ${issues.size} 个问题：\n" + issues.take(4).joinToString("\n") {
                        "· ${it.component}: ${it.description}"
                    }
                    showMsg("硬件快速诊断完成")
                }
            }) { Text("快速诊断") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    val status = diagnostics.collectHardwareStatus()
                    val issues = diagnostics.performFullDiagnostics()
                    diagInfo = "完整诊断：问题 ${issues.size} 个\n${status.toString().take(200)}"
                    showMsg("完整诊断完成")
                }
            }) { Text("完整诊断") }
        }
    }

    SectionCard("远程协助 (#67-#68)") {
        Text(remoteInfo, fontSize = 12.sp)
        Row {
            Button(onClick = {
                val session = remote.requestRemoteAssistance("op_001", "JARVIS 支持")
                remoteInfo = "已发起会话 ${session.sessionId}（${session.status}）"
                showMsg(remoteInfo)
            }) { Text("请求协助") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val active = remote.getActiveSessions()
                remoteInfo = if (active.isEmpty()) "无活动会话" else "活动会话：${active.size}"
                showMsg(remoteInfo)
            }) { Text("查看会话") }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            content()
        }
    }
}

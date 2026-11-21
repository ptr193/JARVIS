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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.pineapple.system.PermissionManager
import com.jarvis.pineapple.ui.permission.rememberOnDemandPermission
import com.jarvis.pineapple.system.CalendarController
import com.jarvis.pineapple.system.CommunicationManager
import com.jarvis.pineapple.system.CrossAppOperator
import com.jarvis.pineapple.system.HardwareDiagnostics
import com.jarvis.pineapple.system.MediaController
import com.jarvis.pineapple.system.NavigationController
import com.jarvis.pineapple.system.PhoneController
import com.jarvis.pineapple.system.SmsController
import com.jarvis.pineapple.system.SystemSettingsController
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * 系统操控：电话 / 短信 / 日程 / 导航 / 系统设置 / 媒体 / 跨应用 /
 * 硬件诊断及本机设备控制。
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
    val diagnostics = remember { GlobalContext.get().get<HardwareDiagnostics>() }

    // 在组合中通过 collectAsState 观察 StateFlow，避免直接读取 .value 导致不重组。
    val reminders by communication.reminders.collectAsState()

    var phoneNumber by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var appPackage by remember { mutableStateOf("") }

    var phoneInfo by remember { mutableStateOf("未操作") }
    var systemInfo by remember { mutableStateOf("未操作") }
    var diagInfo by remember { mutableStateOf("尚未诊断") }
    var scheduleInfo by remember { mutableStateOf("暂无日程") }

    val requestPhone = rememberOnDemandPermission(
        permissions = PermissionManager.PHONE_PERMISSIONS,
        onGranted = {
            val ok = phone.call(phoneNumber.trim())
            phoneInfo = if (ok) "已发起通话" else "拨号失败（缺少 CALL_PHONE 权限）"
            showMsg(phoneInfo)
        },
        onDenied = { phoneInfo = it; showMsg(it) }
    )
    val requestSms = rememberOnDemandPermission(
        permissions = PermissionManager.SMS_PERMISSIONS,
        onGranted = {
            val ok = sms.send(phoneNumber.trim(), message)
            phoneInfo = if (ok) "短信已提交系统发送" else "短信提交失败（请检查权限、号码和内容）"
            showMsg(phoneInfo)
        },
        onDenied = { phoneInfo = it; showMsg(it) }
    )
    val requestCalendar = rememberOnDemandPermission(
        permissions = PermissionManager.CALENDAR_PERMISSIONS,
        onGranted = {
            val now = System.currentTimeMillis()
            val ok = calendar.createEvent(
                CalendarController.Event(
                    title = destination.ifBlank { "JARVIS 日程" },
                    startTime = now + 3_600_000,
                    endTime = now + 7_200_000,
                    description = "由 JARVIS 创建"
                )
            )
            scheduleInfo = if (ok) "日程已写入系统日历" else "创建失败（请检查日历权限及可用日历账户）"
            showMsg(scheduleInfo)
        },
        onDenied = { scheduleInfo = it; showMsg(it) }
    )
    val requestLocation = rememberOnDemandPermission(
        permissions = PermissionManager.LOCATION_PERMISSIONS,
        onGranted = {
            val target = destination.trim()
            scheduleInfo = if (navigation.navigateTo(target)) {
                "已请求地图应用导航：$target"
            } else {
                "导航失败：请输入目的地并确认存在可用地图入口"
            }
            showMsg(scheduleInfo)
        },
        onDenied = { scheduleInfo = it; showMsg(it) }
    )

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
            Button(onClick = { requestPhone() }) { Text("拨打电话") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { requestSms() }) { Text("发送短信") }
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
            Button(onClick = { requestCalendar() }) { Text("创建日程") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { requestLocation() }) { Text("发起导航") }
        }
        Text("提醒事项：${reminders.size} 条 · 行程：${communication.getUpcomingTravels().size} 条", fontSize = 12.sp)
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
                systemInfo = if (settings.setWifiEnabled(true)) "系统已接受开启 WiFi 请求" else "无法切换 WiFi（权限或系统版本限制）"
                showMsg(systemInfo)
            }) { Text("开 WiFi") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                systemInfo = if (settings.setWifiEnabled(false)) "系统已接受关闭 WiFi 请求" else "无法切换 WiFi（权限或系统版本限制）"
                showMsg(systemInfo)
            }) { Text("关 WiFi") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                systemInfo = if (settings.setBluetoothEnabled(true)) "系统已接受开启蓝牙请求" else "无法开启蓝牙（权限、硬件或系统限制）"
                showMsg(systemInfo)
            }) { Text("开蓝牙") }
        }
        Row {
            Button(onClick = {
                systemInfo = if (media.playPause()) "已分发播放/暂停按键" else "媒体按键分发失败"
                showMsg(systemInfo)
            }) { Text("播放/暂停") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                systemInfo = if (media.next()) "已分发下一首按键" else "媒体按键分发失败"
                showMsg(systemInfo)
            }) { Text("下一首") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                systemInfo = if (media.previous()) "已分发上一首按键" else "媒体按键分发失败"
                showMsg(systemInfo)
            }) { Text("上一首") }
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
                val opened = settings.openApp(appPackage.trim())
                systemInfo = if (opened) "已请求打开 ${appPackage.trim()}" else "无法打开：包名无效或应用不可启动"
                showMsg(systemInfo)
            }) { Text("打开应用") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val added = crossApp.addToWhitelist(appPackage.trim())
                systemInfo = "白名单：${crossApp.getWhitelist().joinToString("、")}"
                showMsg(if (added) "已加入跨应用白名单" else "未加入：包名无效、应用不可见或已在白名单中")
            }) { Text("加入白名单") }
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

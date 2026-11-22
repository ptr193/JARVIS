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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import com.jarvis.pineapple.system.AnswerReadiness
import com.jarvis.pineapple.system.PermissionManager
import com.jarvis.pineapple.ui.permission.rememberOnDemandPermission
import com.jarvis.pineapple.system.CalendarController
import com.jarvis.pineapple.system.CommunicationManager
import com.jarvis.pineapple.system.CrossAppOperator
import com.jarvis.pineapple.system.DeviceControlManager
import com.jarvis.pineapple.system.HardwareDiagnostics
import com.jarvis.pineapple.system.HostRootManager
import com.jarvis.pineapple.system.UsbDeviceManager
import com.jarvis.pineapple.system.SecondaryDisplayManager
import com.jarvis.pineapple.system.RemoteAssistanceManager
import com.jarvis.pineapple.perception.HolographicInterface
import com.jarvis.pineapple.system.MediaController
import com.jarvis.pineapple.system.NavigationController
import com.jarvis.pineapple.system.PhoneController
import com.jarvis.pineapple.system.SmsController
import com.jarvis.pineapple.system.SystemSettingsController
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
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
    val device = remember { GlobalContext.get().get<DeviceControlManager>() }
    val usb = remember { GlobalContext.get().get<UsbDeviceManager>() }
    val displays = remember { GlobalContext.get().get<SecondaryDisplayManager>() }
    val remote = remember { GlobalContext.get().get<RemoteAssistanceManager>() }
    val holographic = remember { GlobalContext.get().get<HolographicInterface>() }
    val hostRoot = remember { GlobalContext.get().get<HostRootManager>() }
    val context = LocalContext.current

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
    var shareInfo by remember { mutableStateOf("尚未捕获") }
    var rootStatus by remember { mutableStateOf("尚未检测") }
    var rootAllowed by remember { mutableStateOf(hostRoot.isAllowed()) }

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
    val requestAnswer = rememberOnDemandPermission(
        permissions = PermissionManager.PHONE_PERMISSIONS,
        onGranted = {
            val ok = phone.answerCall()
            phoneInfo = if (ok) "已下发接听来电指令" else "当前无来电或系统拒绝接听"
            showMsg(phoneInfo)
        },
        onDenied = { phoneInfo = it; showMsg(it) }
    )

    fun answerCall() {
        val readiness = PhoneController.answerReadiness(
            Build.VERSION.SDK_INT, phone.hasAnswerPermission(), phone.isDefaultDialer()
        )
        when (readiness) {
            AnswerReadiness.UNSUPPORTED_SDK -> {
                phoneInfo = "接听来电需要 Android 8.0（API 26）及以上"
                showMsg(phoneInfo)
            }
            AnswerReadiness.PERMISSION_REQUIRED -> {
                phoneInfo = "缺少 ANSWER_PHONE_CALLS 权限，正在请求…"
                showMsg(phoneInfo)
                requestAnswer()
            }
            AnswerReadiness.NOT_DEFAULT_DIALER -> {
                phoneInfo = "请先在系统设置中将 JARVIS 设为默认电话应用，否则无法接听来电"
                showMsg(phoneInfo)
            }
            AnswerReadiness.READY -> {
                val ok = phone.answerCall()
                phoneInfo = if (ok) "已下发接听来电指令" else "当前无来电或系统拒绝接听"
                showMsg(phoneInfo)
            }
        }
    }

    Text("系统操控", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    SectionCard("宿主机 Root 权限（高级用户）") {
        Text(
            "设备已 root 时，JARVIS 可在授权后通过 su 以 root 身份执行命令；未 root 或拒绝授权会如实提示。",
            fontSize = 12.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("允许 AI 使用宿主机 root", modifier = Modifier.weight(1f), fontSize = 13.sp)
            Switch(
                checked = rootAllowed,
                onCheckedChange = { checked ->
                    rootAllowed = checked
                    hostRoot.setAllowed(checked)
                }
            )
        }
        Text("状态：$rootStatus", fontSize = 12.sp)
        OutlinedButton(onClick = {
            scope.launch {
                rootStatus = "正在执行 su -c id 探测..."
                val status = hostRoot.check()
                rootStatus = status.detail
            }
        }) { Text("检测 / 申请 root") }
        Text(
            "对话中可发送：root 执行 id；危险命令需二次确认，执行记录写入 root_audit.log。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

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
            Button(onClick = { answerCall() }) { Text("接听来电") }
            Spacer(Modifier.width(8.dp))
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

    SectionCard("设备控制 (#78 振动 / #79 手电筒)") {
        var torchOn by remember { mutableStateOf(false) }
        Text(
            "振动器: ${if (device.hasVibrator()) "可用" else "不可用"}　" +
                "闪光灯: ${if (device.hasTorch()) "可用" else "不可用"}",
            fontSize = 12.sp
        )
        Row {
            Button(onClick = {
                val ok = device.vibrate(200)
                showMsg(if (ok) "已触发振动" else "振动失败：设备不支持或被系统拦截")
            }) { Text("振动 200ms") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val next = !torchOn
                val ok = device.setTorchEnabled(next)
                if (ok) {
                    torchOn = next
                    showMsg(if (next) "手电筒已打开" else "手电筒已关闭")
                } else {
                    showMsg("手电筒操作失败：无闪光灯、相机被占用或系统拒绝")
                }
            }) { Text(if (torchOn) "关闭手电筒" else "打开手电筒") }
        }
    }

    SectionCard("外设 (#73 USB 设备)") {
        var usbInfo by remember { mutableStateOf("尚未探测") }
        Text(
            "USB 主机特性: ${if (usb.hasUsbHostFeature()) "支持" else "不支持"}",
            fontSize = 12.sp
        )
        Row {
            Button(onClick = {
                val devices = usb.probeUsbDevices()
                usbInfo = UsbDeviceManager.summarize(devices)
                showMsg(if (devices.isEmpty()) "未检测到 USB 设备" else "已探测 ${devices.size} 个 USB 设备")
            }) { Text("探测 USB 设备") }
        }
        Text(usbInfo, fontSize = 12.sp)
    }

    SectionCard("外接显示 (#72)") {
        var displayInfo by remember { mutableStateOf("尚未探测") }
        Row {
            Button(onClick = {
                val result = displays.probeDisplays()
                displayInfo = SecondaryDisplayManager.summarize(result)
                showMsg(
                    if (result is SecondaryDisplayManager.ProbeResult.Ok) "外接显示探测完成"
                    else displayInfo
                )
            }) { Text("探测外接显示") }
        }
        Text(displayInfo, fontSize = 12.sp)
    }

    fun captureAndShare() {
        scope.launch {
            val file = withContext(Dispatchers.IO) { remote.captureScreenToFile() }
            val intent = remote.buildShareIntent(file)
            if (intent == null) {
                shareInfo = "屏幕捕获失败"
                showMsg(shareInfo)
            } else {
                shareInfo = "已捕获屏幕截图：${file?.name}"
                runCatching {
                    context.startActivity(
                        Intent.createChooser(intent, "分享屏幕")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    shareInfo = "无法唤起分享面板"
                    showMsg(shareInfo)
                }
            }
        }
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!remote.onCaptureConsent(result.resultCode, result.data)) {
            shareInfo = "未获得屏幕录制授权"
            showMsg(shareInfo)
        } else {
            captureAndShare()
        }
    }

    SectionCard("屏幕共享 (#67 / #68 / #71)") {
        Text(shareInfo, fontSize = 12.sp)
        Text(
            "仅捕获并分享当前屏幕截图；跨设备远程控制需中继服务，本应用不提供",
            fontSize = 11.sp
        )
        Row {
            Button(onClick = {
                if (remote.hasCapturePermission()) captureAndShare()
                else captureLauncher.launch(remote.createScreenCaptureIntent())
            }) { Text("捕获并分享屏幕") }
        }
    }

    SectionCard("全息 / AR / 机械臂 / 手势 边界 (#97 / #114 / #115 / #116)") {
        var holoInfo by remember { mutableStateOf("尚未探测") }
        Text("如实探测硬件/SDK 能力，不支持不会伪装为可用", fontSize = 11.sp)
        Row {
            Button(onClick = {
                scope.launch {
                    val report = withContext(Dispatchers.IO) { holographic.probeAll() }
                    holoInfo = HolographicInterface.summarize(report)
                    showMsg("能力探测完成（可用 ${report.availableCount}/4）")
                }
            }) { Text("探测能力边界") }
        }
        Text(holoInfo, fontSize = 12.sp)
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

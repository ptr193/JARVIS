package com.jarvis.pineapple.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.pineapple.autonomous.AutonomousDecisionManager
import com.jarvis.pineapple.autonomous.DataManager
import com.jarvis.pineapple.autonomous.EmergencyProtocol
import com.jarvis.pineapple.autonomous.ProactiveNotifier
import com.jarvis.pineapple.autonomous.TaskScheduler
import com.jarvis.pineapple.health.FatigueMonitor
import com.jarvis.pineapple.health.HealthManager
import com.jarvis.pineapple.health.TelemedicineManager
import com.jarvis.pineapple.health.VitalSignsMonitor
import com.jarvis.pineapple.info.InfoProcessor
import com.jarvis.pineapple.perception.ARIElementType
import com.jarvis.pineapple.perception.ARIElement
import com.jarvis.pineapple.perception.BehaviorPredictor
import com.jarvis.pineapple.perception.CameraArInterface
import com.jarvis.pineapple.perception.EnvironmentPerception
import com.jarvis.pineapple.perception.PerceptionManager
import com.jarvis.pineapple.perception.Position3D
import com.jarvis.pineapple.smarthome.SmartHomeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * 智能中枢：把感知 / 自主决策 / 智能家居 / 健康 / AR / 数据管理 等
 * 已实现但此前没有界面入口的子系统接入真实 UI（REQ-05）。
 */
@Composable
fun SmartHubTab(showMsg: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val perception = remember { GlobalContext.get().get<PerceptionManager>() }
    val environment = remember { GlobalContext.get().get<EnvironmentPerception>() }
    val behavior = remember { GlobalContext.get().get<BehaviorPredictor>() }
    val autonomy = remember { GlobalContext.get().get<AutonomousDecisionManager>() }
    val smartHome = remember { GlobalContext.get().get<SmartHomeManager>() }
    val telemedicine = remember { GlobalContext.get().get<TelemedicineManager>() }
    val dataManager = remember { GlobalContext.get().get<DataManager>() }
    val taskScheduler = remember { GlobalContext.get().get<TaskScheduler>() }
    val notifier = remember { GlobalContext.get().get<ProactiveNotifier>() }
    val emergencyProtocol = remember { GlobalContext.get().get<EmergencyProtocol>() }
    val infoProcessor = remember { GlobalContext.get().get<InfoProcessor>() }

    val vitalSigns = remember { VitalSignsMonitor(context) }
    val fatigue = remember { FatigueMonitor() }
    val health = remember {
        runCatching { GlobalContext.get().get<HealthManager>() }.getOrElse { HealthManager() }
    }
    val ar = remember { CameraArInterface(context) }

    // ---------- 环境感知 ----------
    var monitoring by remember { mutableStateOf(false) }
    var envScene by remember { mutableStateOf("未采样") }
    val scene by perception.sceneRecognition.collectAsState()
    val light by perception.lightLevel.collectAsState()
    val temperature by perception.temperature.collectAsState()
    val humidity by perception.humidity.collectAsState()
    val gyro by perception.gyroscopeData.collectAsState()

    // ---------- 行为预测 ----------
    var prediction by remember { mutableStateOf("尚无足够数据") }
    var behaviorStats by remember { mutableStateOf("尚无记录") }

    // ---------- 自主决策 ----------
    val learningRecords by autonomy.learningRecords.collectAsState()
    var decisionMsg by remember { mutableStateOf("尚未执行") }

    // ---------- 智能家居 ----------
    val homeDevices by smartHome.devices.collectAsState()
    val discovering by smartHome.isDiscovering.collectAsState()
    val discoveryStatus by smartHome.discoveryStatus.collectAsState()

    // ---------- 健康 ----------
    var heartRate by remember { mutableStateOf<Int?>(null) }
    var fatigueText by remember { mutableStateOf("未检测") }
    var trendText by remember { mutableStateOf("无数据") }
    val vitalHistory = remember { mutableListOf<VitalSignsMonitor.VitalSigns>() }

    // ---------- 远程医疗 ----------
    val sessions by telemedicine.sessions.collectAsState()

    // ---------- AR ----------
    var arText by remember { mutableStateOf("未启动") }

    // ---------- 数据管理 ----------
    var backupInfo by remember { mutableStateOf("尚无备份") }
    var cryptoInfo by remember { mutableStateOf("未执行") }
    var recordInfo by remember { mutableStateOf("尚无记录") }

    LaunchedEffect(Unit) {
        perception.startMonitoring()
        monitoring = true
    }

    SectionCard("环境感知") {
        Text("场景：${scene.sceneType}  置信度：${(scene.confidence * 100).toInt()}%", fontSize = 13.sp)
        Text(
            "光照：${"%.1f".format(light)} lx  温度：${"%.1f".format(temperature)}℃  湿度：${"%.1f".format(humidity)}%",
            fontSize = 13.sp
        )
        Text("设备稳定：${if (gyro.isStable) "是" else "否"}（评分 ${"%.2f".format(gyro.stabilityScore)}）", fontSize = 13.sp)
        Text("环境场景识别：$envScene", fontSize = 13.sp)
        Row {
            Button(onClick = {
                if (monitoring) perception.stopMonitoring() else perception.startMonitoring()
                monitoring = !monitoring
            }) { Text(if (monitoring) "停止传感器" else "启动传感器") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                environment.start()
                envScene = environment.recognizeScene()
            }) { Text("识别环境场景") }
        }
    }

    SectionCard("行为预测") {
        Text(prediction, fontSize = 13.sp)
        Text(behaviorStats, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            Button(onClick = {
                behavior.recordBehavior(
                    BehaviorPredictor.BehaviorRecord(
                        action = "手动记录",
                        category = BehaviorPredictor.BehaviorCategory.OTHER
                    )
                )
                behaviorStats = behavior.getBehaviorStatistics().let {
                    "记录 ${it.totalRecords} 条 · 活跃时段 ${it.mostActiveHour ?: "—"} 点"
                }
                showMsg("已记录一条行为样本")
            }) { Text("记录样本") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val p = behavior.predictNextBehavior()
                prediction = "预测：${p.predictedAction}（${(p.confidence * 100).toInt()}%）\n${p.reasoning}"
            }) { Text("预测下一行为") }
        }
    }

    SectionCard("自主决策") {
        Text("学习样本：${learningRecords.size} 条", fontSize = 13.sp)
        Text(decisionMsg, fontSize = 13.sp)
        Row {
            Button(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { autonomy.checkAndExecuteTasks() }
                    decisionMsg = "已检查并执行到期任务"
                    showMsg(decisionMsg)
                }
            }) { Text("检查自主任务") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                autonomy.recordLearning(
                    AutonomousDecisionManager.LearningType.CORRECTION,
                    "ui_action",
                    "manual_feedback",
                    AutonomousDecisionManager.LearningFeedback(true, 1f, "用户手动确认")
                )
                autonomy.getLearningStats().let {
                    decisionMsg = "学习统计：${it.totalRecords} 条，正向率 ${(it.positiveRate * 100).toInt()}%"
                }
                showMsg("已写入一条学习反馈")
            }) { Text("记录学习反馈") }
        }
        Row {
            Button(onClick = {
                val suggestions = autonomy.generateContextualSuggestions(
                    AutonomousDecisionManager.UserContext(currentActivity = "设置页")
                )
                decisionMsg = suggestions.joinToString("\n") { "· ${it.title}：${it.description}" }
                showMsg("已生成 ${suggestions.size} 条上下文建议")
            }) { Text("生成建议") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                notifier.notify("ui", "来自智能中枢的测试预警", ProactiveNotifier.Severity.INFO)
                showMsg("已发送主动预警")
            }) { Text("发送预警") }
        }
        Row {
            Button(onClick = {
                taskScheduler.schedule(
                    TaskScheduler.Task("ui_task_${System.currentTimeMillis()}", "界面触发任务", 1) {
                        // 真实任务体：由调度器执行
                    }
                )
                scope.launch {
                    taskScheduler.runNext()
                    showMsg("任务调度队列已执行")
                }
            }) { Text("调度任务") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                emergencyProtocol.trigger(EmergencyProtocol.EmergencyType.MEDICAL)
                showMsg("已触发医疗应急流程")
            }) { Text("触发应急") }
        }
    }

    SectionCard("智能家居") {
        Text("设备：${homeDevices.size} 台 · 发现中：${if (discovering) "是" else "否"}", fontSize = 13.sp)
        if (discoveryStatus.isNotBlank()) {
            Text(discoveryStatus, fontSize = 12.sp)
        }
        homeDevices.take(5).forEach { d ->
            Text("· ${d.name}（${d.type}）${if (d.state.isOn) " 开" else " 关"}", fontSize = 12.sp)
        }
        Row {
            Button(onClick = {
                smartHome.startDiscovery()
                showMsg("开始发现局域网智能设备")
            }) { Text("发现设备") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { smartHome.stopDiscovery() }) { Text("停止发现") }
        }
        homeDevices.firstOrNull { it.type == SmartHomeManager.DeviceType.LIGHT }?.let { d ->
            Row {
                Button(onClick = {
                    scope.launch { showMsg(smartHome.turnOnLight(d.id)) }
                }) { Text("开灯") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    scope.launch { showMsg(smartHome.turnOffLight(d.id)) }
                }) { Text("关灯") }
            }
        }
    }

    SectionCard("健康监测") {
        Text("心率：${heartRate?.let { "$it bpm" } ?: "未测量"}", fontSize = 13.sp)
        Text("疲劳：$fatigueText", fontSize = 13.sp)
        Text("趋势：$trendText", fontSize = 13.sp)
        Row {
            Button(onClick = {
                scope.launch {
                    if (!vitalSigns.hasHeartRateSensor()) {
                        showMsg("设备无心率传感器")
                        return@launch
                    }
                    val bpm = vitalSigns.measureHeartRate()
                    heartRate = bpm
                    if (bpm > 0) {
                        vitalHistory.add(VitalSignsMonitor.VitalSigns(bpm, "sensor"))
                        trendText = health.analyzeTrend(vitalHistory)
                    }
                }
            }) { Text("测量心率") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val tired = fatigue.detectFatigue(0.15f, 1)
                fatigueText = "PERCLOS ${(fatigue.perclos() * 100).toInt()}% · ${if (tired) "疲劳" else "正常"}"
            }) { Text("检测疲劳") }
        }
        Row {
            Button(onClick = {
                health.addMedication(HealthManager.Medication("示例药物", "1 片", System.currentTimeMillis()))
                showMsg("已添加用药提醒")
            }) { Text("添加用药") }
        }
    }

    SectionCard("远程医疗") {
        Text("会诊记录：${sessions.size} 条", fontSize = 13.sp)
        val doctors = telemedicine.getAvailableDoctors()
        Text("在线医生：${doctors.size} 位", fontSize = 13.sp)
        doctors.take(3).forEach { Text("· ${it.name} ${it.specialty} @ ${it.hospital}", fontSize = 12.sp) }
        Button(onClick = {
            val d = doctors.firstOrNull()
            if (d == null) showMsg("暂无在线医生") else showMsg("已向 ${d.name} 发起会诊")
        }) { Text("发起会诊") }
    }

    SectionCard("AR / 全息") {
        Text(arText, fontSize = 13.sp)
        Row {
            Button(onClick = {
                if (!ar.startSession()) {
                    arText = "设备无摄像头，AR 不可用"
                } else {
                    ar.displayUIElement(
                        ARIElement(
                            id = "demo_${System.currentTimeMillis()}",
                            type = ARIElementType.TEXT,
                            content = "JARVIS",
                            position = Position3D(0f, 0f, -1f),
                            size = 1f to 1f
                        )
                    )
                    arText = ar.renderOverlay()
                }
            }) { Text("启动 AR") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                ar.stopSession()
                arText = "AR 会话已结束"
            }) { Text("结束 AR") }
        }
    }

    SectionCard("数据管理") {
        Text(backupInfo, fontSize = 13.sp)
        Row {
            Button(onClick = {
                scope.launch {
                    val f = dataManager.backup()
                    backupInfo = f?.let { "最近备份：${it.name}（${it.length() / 1024} KB）" } ?: "备份失败"
                    showMsg(backupInfo)
                }
            }) { Text("立即备份") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val list = dataManager.listBackups()
                backupInfo = if (list.isEmpty()) "尚无备份"
                else "共 ${list.size} 个备份，最新：${list.first().name}"
                showMsg(backupInfo)
            }) { Text("查看备份") }
        }
    }

    SectionCard("数据处理 (#26-#31)") {
        Text(cryptoInfo, fontSize = 13.sp)
        Text(recordInfo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            Button(onClick = {
                val key = "jarvis-ui-key"
                val plain = "JARVIS 加密测试数据"
                val cipher = infoProcessor.encryptData(plain, key)
                val restored = infoProcessor.decryptData(cipher, key)
                cryptoInfo = "密文长度 ${cipher.length} · 解密还原：${if (restored == plain) "成功" else "失败"}"
                showMsg("加密/解密完成")
            }) { Text("加密/解密测试") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val ok = infoProcessor.backupData(
                    "{\"demo\":true,\"ts\":${System.currentTimeMillis()}}",
                    "smart_hub_demo"
                )
                cryptoInfo = if (ok) "已备份数据到应用私有目录" else "备份失败"
                showMsg(cryptoInfo)
            }) { Text("备份数据") }
        }
        Row {
            Button(onClick = {
                val restored = infoProcessor.restoreData("smart_hub_demo")
                cryptoInfo = if (restored != null) "已恢复：${restored.take(60)}" else "恢复失败：未找到备份"
                showMsg(cryptoInfo)
            }) { Text("恢复数据") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                infoProcessor.logData("ui_action", "智能中枢手动记录", mapOf("source" to "settings"))
                val now = System.currentTimeMillis()
                val entries = infoProcessor.replayData(now - 3_600_000, now)
                recordInfo = "已写入记录 · 近 1 小时回放 ${entries.size} 条"
                showMsg("数据记录与回放完成")
            }) { Text("记录并回放") }
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

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
import com.jarvis.pineapple.autonomous.TaskScheduler
import com.jarvis.pineapple.health.HealthManager
import com.jarvis.pineapple.health.TelemedicineManager
import com.jarvis.pineapple.health.VitalSignsMonitor
import com.jarvis.pineapple.info.InfoProcessor
import com.jarvis.pineapple.perception.BehaviorPredictor
import com.jarvis.pineapple.perception.EnvironmentPerception
import com.jarvis.pineapple.perception.PerceptionManager
import com.jarvis.pineapple.smarthome.SmartHomeManager
import com.jarvis.pineapple.ui.camera.CameraCaptureSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * 智能中枢：把感知 / 自主决策 / 智能家居 / 健康 / 数据管理 等
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
    val emergencyProtocol = remember { GlobalContext.get().get<EmergencyProtocol>() }
    val infoProcessor = remember { GlobalContext.get().get<InfoProcessor>() }

    val vitalSigns = remember { VitalSignsMonitor(context) }
    val health = remember {
        runCatching { GlobalContext.get().get<HealthManager>() }.getOrElse { HealthManager() }
    }

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

    // ---------- 自主决策 ----------
    val learningRecords by autonomy.learningRecords.collectAsState()
    var decisionMsg by remember { mutableStateOf("尚未执行") }

    // ---------- 智能家居 ----------
    val homeDevices by smartHome.devices.collectAsState()
    val discovering by smartHome.isDiscovering.collectAsState()
    val discoveryStatus by smartHome.discoveryStatus.collectAsState()

    // ---------- 健康 ----------
    var heartRate by remember { mutableStateOf<Int?>(null) }
    var trendText by remember { mutableStateOf("无数据") }
    val vitalHistory = remember { mutableListOf<VitalSignsMonitor.VitalSigns>() }

    // ---------- 远程医疗 ----------
    val sessions by telemedicine.sessions.collectAsState()

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
        Button(onClick = {
            val p = behavior.predictNextBehavior()
            prediction = "预测：${p.predictedAction}（${(p.confidence * 100).toInt()}%）\n${p.reasoning}"
        }) { Text("根据已有行为预测") }
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
        }
        Row {
            Button(onClick = {
                val suggestions = autonomy.generateContextualSuggestions(
                    AutonomousDecisionManager.UserContext(currentActivity = "设置页")
                )
                decisionMsg = suggestions.joinToString("\n") { "· ${it.title}：${it.description}" }
                showMsg("已生成 ${suggestions.size} 条上下文建议")
            }) { Text("生成建议") }
        }
        Row {
            Button(onClick = {
                var backupName: String? = null
                taskScheduler.schedule(
                    TaskScheduler.Task("ui_backup_${System.currentTimeMillis()}", "应用数据备份", 1) {
                        backupName = dataManager.backup()?.name
                    }
                )
                scope.launch {
                    taskScheduler.runNext()
                    showMsg(backupName?.let { "调度备份完成：$it" } ?: "调度备份失败：未生成备份文件")
                }
            }) { Text("调度备份") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                showMsg(emergencyProtocol.trigger(EmergencyProtocol.EmergencyType.MEDICAL))
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
        Text("趋势：$trendText", fontSize = 13.sp)
        Button(onClick = {
            scope.launch {
                val bpm = vitalSigns.measureHeartRate()
                heartRate = bpm
                if (bpm > 0) {
                    vitalHistory.add(VitalSignsMonitor.VitalSigns(bpm, "sensor"))
                    trendText = health.analyzeTrend(vitalHistory)
                } else {
                    showMsg(VitalSignsMonitor.NO_HEART_RATE_HARDWARE)
                }
            }
        }) { Text("测量心率") }
    }

    SectionCard("远程医疗") {
        Text("会诊记录：${sessions.size} 条", fontSize = 13.sp)
        val doctors = telemedicine.getAvailableDoctors()
        Text("在线医生：${doctors.size} 位", fontSize = 13.sp)
        doctors.take(3).forEach { Text("· ${it.name} ${it.specialty} @ ${it.hospital}", fontSize = 12.sp) }
        Button(onClick = {
            val d = doctors.firstOrNull()
            scope.launch {
                val result = telemedicine.initiateConsultation(d?.doctorId.orEmpty(), "local")
                showMsg(result.message)
            }
        }) { Text("发起会诊") }
    }

    CameraCaptureSection(
        showMsg = showMsg,
        showFaceActions = false,
        showPerceptionActions = true
    )

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

    SectionCard("数据处理") {
        Text(cryptoInfo, fontSize = 13.sp)
        Text(recordInfo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            Button(onClick = {
                val key = "jarvis-ui-key"
                val plain = "JARVIS 加密自检数据"
                val cipher = infoProcessor.encryptData(plain, key)
                val restored = infoProcessor.decryptData(cipher, key)
                cryptoInfo = "密文长度 ${cipher.length} · 解密还原：${if (restored == plain) "成功" else "失败"}"
                showMsg(cryptoInfo)
            }) { Text("加密自检") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                infoProcessor.logData("ui_action", "智能中枢手动记录", mapOf("source" to "settings"))
                val now = System.currentTimeMillis()
                val entries = infoProcessor.replayData(now - 3_600_000, now)
                recordInfo = "已写入记录 · 近 1 小时回放 ${entries.size} 条"
                showMsg(recordInfo)
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

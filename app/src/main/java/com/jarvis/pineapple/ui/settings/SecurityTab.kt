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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.jarvis.pineapple.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * 安全中心：把 SecurityManager 的 #34-#48 能力接入真实 UI（REQ-05）。
 * 人脸/虹膜的原始图像采集需要相机预览，此处覆盖风险评估、权限、反入侵、
 * 防御部署、设备锁定与远程擦除等可直接调用的能力。
 */
@Composable
fun SecurityTab(showMsg: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val security = remember { GlobalContext.get().get<SecurityManager>() }
    val antiTheft = remember { com.jarvis.pineapple.security.AntiTheftManager(context) }

    var securityRisk by remember { mutableStateOf("点击下方按钮评估") }
    var privacyRisk by remember { mutableStateOf("点击下方按钮评估") }
    var policyEval by remember { mutableStateOf("点击下方按钮评估") }
    var permissionInfo by remember { mutableStateOf("点击下方按钮扫描") }
    var intrusionInfo by remember { mutableStateOf("尚未检测") }
    var defenseInfo by remember { mutableStateOf("尚未部署") }
    var showWipeConfirm by remember { mutableStateOf(false) }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text("远程擦除设备数据") },
            text = { Text("此操作将清空设备数据且不可恢复，确认继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showWipeConfirm = false
                    val ok = security.remoteWipeData()
                    if (ok) showMsg("已下发擦除指令")
                    else {
                        showMsg("擦除失败：设备管理器未激活")
                        runCatching {
                            val activity = context as? android.app.Activity
                            if (activity != null) antiTheft.requestAdmin(activity)
                            else context.startActivity(
                                SecurityManager.adminActivationIntent().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }) { Text("确认擦除") }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }) { Text("取消") }
            }
        )
    }

    Text("安全中心", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    SectionCard("安全风险评估 (#45)") {
        Text(securityRisk, fontSize = 13.sp)
        Button(onClick = {
            val r = security.assessSecurityRisk()
            securityRisk = "风险等级：${r.overallRiskLevel}（评分 ${"%.2f".format(r.riskScore)}）\n" +
                "威胁向量：${r.threatVectors.size} · 漏洞：${r.vulnerabilities.size}\n" +
                r.recommendations.take(3).joinToString("\n") { "· $it" }
            showMsg("安全风险评估完成")
        }) { Text("评估安全风险") }
    }

    SectionCard("隐私风险评估 (#44)") {
        Text(privacyRisk, fontSize = 13.sp)
        Button(onClick = {
            val r = security.assessPrivacyRisk()
            privacyRisk = "风险等级：${r.overallRiskLevel}（评分 ${"%.2f".format(r.riskScore)}）\n" +
                "风险项：${r.riskFactors.size}\n" +
                r.recommendations.take(3).joinToString("\n") { "· $it" }
            showMsg("隐私风险评估完成")
        }) { Text("评估隐私风险") }
    }

    SectionCard("安全策略效能 (#43)") {
        Text(policyEval, fontSize = 13.sp)
        Button(onClick = {
            val r = security.evaluateSecurityPolicy()
            policyEval = "总体评分：${"%.2f".format(r.overallScore)}\n" +
                r.policyEffectiveness.entries.take(5).joinToString("\n") { "· ${it.key}：${"%.2f".format(it.value)}" }
            showMsg("安全策略评估完成")
        }) { Text("评估策略效能") }
    }

    SectionCard("权限使用 (#38 / #42)") {
        Text(permissionInfo, fontSize = 13.sp)
        Button(onClick = {
            scope.launch {
                val records = withContext(Dispatchers.IO) { security.checkPermissionUsage() }
                val sensitive = records.count { it.isSensitive }
                permissionInfo = "共 ${records.size} 条权限使用记录，其中敏感 ${sensitive} 条\n" +
                    records.take(4).joinToString("\n") { "· ${it.permission} ← ${it.packageName}" }
                showMsg("权限扫描完成")
            }
        }) { Text("扫描权限使用") }
    }

    SectionCard("反入侵与防御 (#46 / #47)") {
        Text(intrusionInfo, fontSize = 13.sp)
        Text(defenseInfo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            Button(onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { security.detectIntrusion() }
                    intrusionInfo = if (r.isIntrusionDetected)
                        "检测到 ${r.alerts.size} 条入侵告警\n" +
                            r.alerts.take(3).joinToString("\n") { "· ${it.type}: ${it.description}" }
                    else "未检测到入侵行为"
                    showMsg("反入侵检测完成")
                }
            }) { Text("检测入侵") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { security.deployDefenseStrategy() }
                    defenseInfo = if (r.isSuccessful)
                        "已部署 ${r.deployedStrategies.size} 项防御：${r.deployedStrategies.joinToString("、")}"
                    else "防御部署失败：${r.message}"
                    showMsg("防御策略部署完成")
                }
            }) { Text("部署防御") }
        }
    }

    SectionCard("设备锁定与擦除 (#39 / #41)") {
        Row {
            Button(onClick = {
                val ok = security.lockDevice()
                if (ok) showMsg("设备已锁定")
                else {
                    showMsg("锁定失败：设备管理器未激活")
                    runCatching {
                        val activity = context as? android.app.Activity
                        if (activity != null) antiTheft.requestAdmin(activity)
                        else context.startActivity(
                            SecurityManager.adminActivationIntent().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }) { Text("立即锁定") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val ok = security.remoteLockDevice()
                showMsg(if (ok) "远程锁定已下发" else "远程锁定失败")
            }) { Text("远程锁定") }
        }
        Row {
            OutlinedButton(onClick = { showWipeConfirm = true }) { Text("远程擦除") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val ok = security.executeSecurityProtocol(SecurityManager.SecurityProtocol.LOCKDOWN)
                showMsg(if (ok) "已执行安全协议：LOCKDOWN" else "安全协议执行失败")
            }) { Text("执行安全协议") }
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

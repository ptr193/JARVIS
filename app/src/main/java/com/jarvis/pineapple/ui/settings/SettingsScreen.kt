package com.jarvis.pineapple.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 设置界面（10 个标签页，可滑动）
 * ① 代理 ② 服务 ③ 工具 ④ 虚拟机设置 ⑤ 状态 ⑥ 仓库 ⑦ 连接设备 ⑧ 语言语音 ⑨ 日志 ⑩ 通用
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val tabs = listOf("代理", "服务", "工具", "虚拟机", "状态", "仓库", "连接设备", "语言语音", "日志", "通用")
    var selected by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selected, modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = selected == i, onClick = { selected = i }, text = { Text(title) })
                }
            }
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                when (selected) {
                    0 -> AgentTab()
                    1 -> ServiceTab()
                    2 -> ToolsTab()
                    3 -> VmSettingsTab()
                    4 -> StatusTab()
                    5 -> RepoTab()
                    6 -> ConnectedDevicesTab()
                    7 -> LanguageVoiceTab()
                    8 -> LogTab()
                    9 -> GeneralTab()
                }
            }
        }
    }
}

// ① 代理：人格/灵魂设置
@Composable
private fun AgentTab() {
    var prompt by remember { mutableStateOf(com.jarvis.pineapple.personality.DEFAULT_SYSTEM_PROMPT.take(200) + "...") }
    Text("人格（灵魂）设置", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = prompt, onValueChange = { prompt = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        label = { Text("人格描述") }
    )
    Row {
        Button(onClick = {}) { Text("保存") }
        Button(onClick = {}) { Text("重置") }
    }
}

// ② 服务：API + 服务器
@Composable
private fun ServiceTab() {
    Text("AI / API / 服务器配置", style = MaterialTheme.typography.titleMedium)
    var apiKey by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") })
    OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text("服务器地址") })
    Button(onClick = {}) { Text("保存配置") }
}

// ③ 工具：技能
@Composable
private fun ToolsTab() {
    Text("技能管理", style = MaterialTheme.typography.titleMedium)
    Button(onClick = {}) { Text("导入技能") }
}

// ④ 虚拟机设置
@Composable
private fun VmSettingsTab() {
    Text("虚拟机配置", style = MaterialTheme.typography.titleMedium)
}

// ⑤ 状态
@Composable
private fun StatusTab() {
    Text("设备状态", style = MaterialTheme.typography.titleMedium)
    Text("CPU: --%")
    Text("内存: --")
    Text("温度: --")
    Text("电池: --")
}

// ⑥ 仓库
@Composable
private fun RepoTab() {
    Text("仓库管理", style = MaterialTheme.typography.titleMedium)
    var repo by remember { mutableStateOf("") }
    OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("GitHub/GitLab 仓库地址") })
    Button(onClick = {}) { Text("添加") }
}

// ⑦ 连接设备
@Composable
private fun ConnectedDevicesTab() {
    Text("连接设备", style = MaterialTheme.typography.titleMedium)
    Button(onClick = {}) { Text("扫描设备") }
}

// ⑧ 语言语音
@Composable
private fun LanguageVoiceTab() {
    Text("语言与语音", style = MaterialTheme.typography.titleMedium)
    Text("声线：JARVIS（默认）")
    Text("语言：中文（100+ 可选）")
}

// ⑨ 日志
@Composable
private fun LogTab() {
    Text("日志", style = MaterialTheme.typography.titleMedium)
    Button(onClick = {}) { Text("导出日志") }
}

// ⑩ 通用
@Composable
private fun GeneralTab() {
    Text("通用设置", style = MaterialTheme.typography.titleMedium)
    var voiceWake by remember { mutableStateOf(true) }
    var powerWake by remember { mutableStateOf(true) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("语音唤醒")
        Switch(checked = voiceWake, onCheckedChange = { voiceWake = it })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("电源键唤醒")
        Switch(checked = powerWake, onCheckedChange = { powerWake = it })
    }
}

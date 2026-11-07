package com.jarvis.pineapple.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import com.jarvis.pineapple.model.DownloadStatus
import com.jarvis.pineapple.model.ModelDownloadManager
import com.jarvis.pineapple.model.ModelDownloadState
import com.jarvis.pineapple.plugin.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置界面（规格书 3.3 十标签可滑动 + 扩展入口）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tabs = SettingsTabs.ALL_TABS
    val initialIndex = remember {
        val saved = context.getSharedPreferences(SettingsTabs.PREFS, Context.MODE_PRIVATE)
            .getInt(SettingsTabs.KEY_INDEX, 0)
        SettingsTabs.clampIndex(saved, tabs.size)
    }
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { tabs.size })
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMsg: (String) -> Unit = { msg ->
        scope.launch { snackbarHostState.showSnackbar(msg) }
        Unit
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            context.getSharedPreferences(SettingsTabs.PREFS, Context.MODE_PRIVATE)
                .edit().putInt(SettingsTabs.KEY_INDEX, page).apply()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            ScrollableTabRow(selectedTabIndex = pagerState.currentPage, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, tab ->
                    Tab(
                        selected = pagerState.currentPage == i,
                        onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                        text = { Text(tab.title) }
                    )
                }
            }
            val current = tabs[pagerState.currentPage]
            Text(
                text = current.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    when (page) {
                        0 -> AgentTab(showMsg)
                        1 -> ServiceTab(showMsg)
                        2 -> ToolsTab(showMsg)
                        3 -> VmSettingsTab(showMsg)
                        4 -> StatusTab(showMsg)
                        5 -> RepoTab(showMsg)
                        6 -> ConnectedDevicesTab(showMsg)
                        7 -> LanguageVoiceTab(showMsg)
                        8 -> LogTab(showMsg = showMsg)
                        9 -> GeneralTab(showMsg)
                        10 -> SmartHubTab(showMsg)
                        11 -> SecurityTab(showMsg)
                        12 -> SystemControlTab(showMsg)
                    }
                }
            }
        }
    }
}

// ① 代理：人格/灵魂设置
@Composable
private fun AgentTab(showMsg: (String) -> Unit) {
    val context = LocalContext.current
    val repo = remember { org.koin.core.context.GlobalContext.get().get<com.jarvis.pineapple.personality.PersonalityRepository>() }
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        prompt = repo.current().systemPrompt
        loaded = true
    }

    Text("人格（灵魂）设置", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = prompt, onValueChange = { prompt = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        label = { Text("人格描述（system prompt）") },
        enabled = loaded,
        maxLines = 14
    )
    Row {
        Button(onClick = {
            scope.launch {
                repo.updateSystemPrompt(prompt)
                showMsg("人格配置已保存并生效")
            }
        }) { Text("保存") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            prompt = com.jarvis.pineapple.personality.DEFAULT_SYSTEM_PROMPT
            scope.launch {
                repo.resetToDefault()
                showMsg("已重置为默认人格")
            }
        }) { Text("重置") }
    }
}

// ② 服务：API + 服务器 + 模型下载
@Composable
private fun ServiceTab(showMsg: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }

    // 加载已保存的配置
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("jarvis_service", Context.MODE_PRIVATE)
        apiKey = prefs.getString("api_key", "") ?: ""
        serverUrl = prefs.getString("server_url", "") ?: ""
    }

    Text("AI / API / 服务器配置", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") })
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text("服务器地址") })
    Spacer(Modifier.height(8.dp))
    Button(onClick = {
        val prefs = context.getSharedPreferences("jarvis_service", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("api_key", apiKey)
            .putString("server_url", serverUrl)
            .apply()
        showMsg("服务配置已保存")
    }) { Text("保存配置") }

    Spacer(Modifier.height(16.dp))
    Text("模型下载管理", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    // 模型下载列表
    val models = remember { ModelDownloadManager(context) }
    val downloadStates by models.downloadStates.collectAsState()

    Column {
        models.getModelConfigs().forEach { config ->
            val state = downloadStates[config.id] ?: ModelDownloadState(
                modelId = config.id,
                modelName = config.name
            )
            ModelDownloadItem(
                state = state,
                onDownload = {
                    scope.launch {
                        models.downloadModel(config.id)
                    }
                },
                onDelete = {
                    models.deleteModel(config.id)
                }
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ModelDownloadItem(
    state: ModelDownloadState,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.modelName, fontWeight = FontWeight.Bold)
                    Text(
                        text = when (state.status) {
                            DownloadStatus.NOT_DOWNLOADED -> "未下载"
                            DownloadStatus.DOWNLOADING -> "下载中... ${(state.progress * 100).toInt()}%"
                            DownloadStatus.COMPLETED -> "已下载"
                            DownloadStatus.FAILED -> "下载失败: ${state.errorMessage}"
                            DownloadStatus.PAUSED -> "已暂停"
                            DownloadStatus.VERIFYING -> "校验中..."
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    if (state.status == DownloadStatus.NOT_DOWNLOADED || state.status == DownloadStatus.FAILED) {
                        IconButton(onClick = onDownload) {
                            Icon(Icons.Filled.Download, contentDescription = "下载")
                        }
                    }
                    if (state.status == DownloadStatus.COMPLETED) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
            if (state.status == DownloadStatus.DOWNLOADING) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ③ 工具：技能（#139 安全程序分化 —— 真实插件加载 / 安装 / 卸载）
@Composable
private fun ToolsTab(showMsg: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pluginManager = remember {
        org.koin.core.context.GlobalContext.get().get<PluginManager>()
    }
    val installed by pluginManager.plugins.collectAsState()

    // 首次进入扫描插件目录，恢复已安装技能
    LaunchedEffect(Unit) { pluginManager.reload() }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val displayName = queryDisplayName(context, uri)
                ?: "plugin_${System.currentTimeMillis()}.apk"
            val tmp = File(context.cacheDir, displayName)
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                        true
                    } ?: false
                }.getOrDefault(false)
            }
            val id = if (copied) pluginManager.install(tmp) else null
            tmp.delete()
            showMsg(
                if (id != null) "技能已导入并启用: $id"
                else "导入失败：需为有效插件包(.apk/.dex/.jar)且签名匹配"
            )
        }
    }

    Text("技能管理", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text("已安装技能（${installed.size}）", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    if (installed.isEmpty()) {
        Text(
            "暂无已安装技能，点击下方按钮导入插件包",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
    }
    LazyColumn(modifier = Modifier.height(200.dp)) {
        items(installed, key = { it.metadata.id }) { plugin ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(plugin.metadata.name)
                        Text(
                            "${plugin.metadata.id} v${plugin.metadata.version}" +
                                if (plugin.metadata.author.isNotBlank()) " · ${plugin.metadata.author}" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = {
                        pluginManager.uninstall(plugin.metadata.id)
                        showMsg("已卸载: ${plugin.metadata.name}")
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "卸载")
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { importer.launch(arrayOf("*/*")) }) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text("导入技能")
    }
}

/** 读取 SAF 文档的显示名（含扩展名）。 */
private fun queryDisplayName(context: Context, uri: android.net.Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}

// ④ 虚拟机设置
@Composable
private fun VmSettingsTab(showMsg: (String) -> Unit) {
    val context = LocalContext.current
    var cpuCores by remember { mutableStateOf(2) }
    var memoryMb by remember { mutableStateOf(2048) }
    var diskGb by remember { mutableStateOf(20) }
    var enableRoot by remember { mutableStateOf(true) }

    // 加载已保存的配置
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("jarvis_vm_config", Context.MODE_PRIVATE)
        cpuCores = prefs.getInt("cpu_cores", 2)
        memoryMb = prefs.getInt("memory_mb", 2048)
        diskGb = prefs.getInt("disk_gb", 20)
        enableRoot = prefs.getBoolean("enable_root", true)
    }

    Text("虚拟机配置", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = cpuCores.toString(),
        onValueChange = { cpuCores = it.toIntOrNull() ?: 2 },
        label = { Text("CPU 核数") },
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = memoryMb.toString(),
        onValueChange = { memoryMb = it.toIntOrNull() ?: 2048 },
        label = { Text("内存 (MB)") },
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = diskGb.toString(),
        onValueChange = { diskGb = it.toIntOrNull() ?: 20 },
        label = { Text("磁盘 (GB)") },
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("启用 Root 权限")
        Switch(checked = enableRoot, onCheckedChange = { enableRoot = it })
    }

    Spacer(Modifier.height(8.dp))
    Button(onClick = {
        val prefs = context.getSharedPreferences("jarvis_vm_config", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("cpu_cores", cpuCores)
            .putInt("memory_mb", memoryMb)
            .putInt("disk_gb", diskGb)
            .putBoolean("enable_root", enableRoot)
            .apply()
        showMsg("虚拟机配置已保存")
    }) { Text("保存配置") }
}

// ⑤ 状态
@Composable
private fun StatusTab(showMsg: (String) -> Unit) {
    val context = LocalContext.current
    val deviceMonitor = remember { com.jarvis.pineapple.system.DeviceMonitor(context) }
    val status by deviceMonitor.status.collectAsState()

    LaunchedEffect(deviceMonitor) {
        while (true) {
            deviceMonitor.refresh()
            kotlinx.coroutines.delay(2_000)
        }
    }

    Text("设备状态", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            StatusItem(
                "CPU 使用率",
                status.cpuUnavailableReason ?: "${status.cpuUsage.toInt()}%"
            )
            StatusItem("内存使用", "${status.memoryUsageMb} / ${status.totalMemoryMb} MB")
            StatusItem("CPU 温度", "${status.temperature}°C")
            StatusItem("电池电量", "${status.batteryLevel}%")
            StatusItem("电池温度", "${status.batteryTemperature}°C")
        }
    }

    Spacer(Modifier.height(8.dp))
    Button(onClick = { deviceMonitor.refresh() }) { Text("立即刷新") }
}

@Composable
private fun StatusItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

// ⑥ 仓库
@Composable
private fun RepoTab(showMsg: (String) -> Unit) {
    val context = LocalContext.current
    var repo by remember { mutableStateOf("") }

    Text("仓库管理", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("GitHub/GitLab 仓库地址") })
    Spacer(Modifier.height(8.dp))
    Button(onClick = {
        if (repo.isNotBlank()) {
            val prefs = context.getSharedPreferences("jarvis_repos", Context.MODE_PRIVATE)
            val repos = prefs.getStringSet("repos", emptySet())?.toMutableSet() ?: mutableSetOf()
            repos.add(repo)
            prefs.edit().putStringSet("repos", repos).apply()
            repo = ""
            showMsg("仓库已添加")
        }
    }) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text("添加")
    }

    Spacer(Modifier.height(8.dp))
    Text("已添加仓库", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))

    val repos = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("jarvis_repos", Context.MODE_PRIVATE)
        repos.clear()
        repos.addAll(prefs.getStringSet("repos", emptySet()) ?: emptySet())
    }

    LazyColumn(modifier = Modifier.height(200.dp)) {
        items(repos) { repoUrl ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(repoUrl, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val prefs = context.getSharedPreferences("jarvis_repos", Context.MODE_PRIVATE)
                        val currentRepos = prefs.getStringSet("repos", emptySet())?.toMutableSet() ?: mutableSetOf()
                        currentRepos.remove(repoUrl)
                        prefs.edit().putStringSet("repos", currentRepos).apply()
                        repos.remove(repoUrl)
                        showMsg("仓库已移除")
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
            }
        }
    }
}

// ⑦ 连接设备
@Composable
private fun ConnectedDevicesTab(showMsg: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloud = remember {
        runCatching { org.koin.core.context.GlobalContext.get().get<com.jarvis.pineapple.cloud.CloudSyncManager>() }
            .getOrNull()
    }
    val prefs = remember { context.getSharedPreferences("jarvis_devices", Context.MODE_PRIVATE) }

    var account by remember { mutableStateOf(prefs.getString("account", "") ?: "") }
    var password by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(cloud?.isLoggedIn() == true) }
    var pairingCode by remember { mutableStateOf("") }
    val devices = remember { mutableStateListOf<com.jarvis.pineapple.cloud.ConnectedDevice>() }

    LaunchedEffect(Unit) {
        val savedToken = prefs.getString("token", null)
        val savedAccount = prefs.getString("account", null)
        if (cloud != null && savedToken != null && savedAccount != null) {
            cloud.restoreSession(savedAccount, savedToken)
            loggedIn = true
        }
        while (true) {
            val c = cloud
            if (c != null && c.isCloudAvailable() && c.isLoggedIn()) {
                val latest = c.discoverDevices()
                devices.clear()
                devices.addAll(latest)
            }
            kotlinx.coroutines.delay(8_000)
        }
    }

    Text("连接设备", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        if (cloud?.isCloudAvailable() == true) "云端服务已配置" else "云端未配置（CLOUD_SYNC_BASE_URL 为空），仅支持本机联动",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    // 账号接入（#134）
    Text("账号接入", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    if (!loggedIn) {
        OutlinedTextField(
            value = account,
            onValueChange = { account = it },
            label = { Text("账号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Row {
            Button(onClick = {
                val c = cloud
                if (c == null) { showMsg("云端模块不可用"); return@Button }
                scope.launch {
                    val ok = c.login(account.trim(), password)
                    if (ok) {
                        loggedIn = true
                        val token = c.accountToken.value
                        prefs.edit().putString("account", account.trim()).putString("token", token).apply()
                        devices.clear()
                        devices.addAll(c.discoverDevices())
                        showMsg("登录成功，已接入多设备")
                    } else {
                        showMsg(if (c.isCloudAvailable()) "登录失败，请检查账号密码" else "云端未配置，无法登录")
                    }
                }
            }) { Text("登录") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val c = cloud
                if (c == null) { showMsg("云端模块不可用"); return@OutlinedButton }
                scope.launch {
                    val ok = c.register(account.trim(), password, android.os.Build.MODEL)
                    if (ok) {
                        loggedIn = true
                        val token = c.accountToken.value
                        prefs.edit().putString("account", account.trim()).putString("token", token).apply()
                        showMsg("注册成功并已绑定当前设备")
                    } else {
                        showMsg(if (c.isCloudAvailable()) "注册失败" else "云端未配置，无法注册")
                    }
                }
            }) { Text("注册并绑定") }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已登录：${cloud?.accountName?.value ?: account}", fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = {
                cloud?.logout()
                loggedIn = false
                password = ""
                prefs.edit().remove("token").apply()
                devices.clear()
                showMsg("已退出登录")
            }) { Text("退出") }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row {
        Button(onClick = {
            val c = cloud
            if (c == null) { showMsg("云端模块不可用"); return@Button }
            if (!c.isCloudAvailable()) { showMsg("云端未配置，无法发现远程设备"); return@Button }
            scope.launch {
                showMsg("正在扫描设备...")
                devices.clear()
                devices.addAll(c.discoverDevices())
                showMsg("发现 ${devices.size} 台设备")
            }
        }) { Text("扫描设备") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = {
            val c = cloud
            if (c == null) { showMsg("云端模块不可用"); return@OutlinedButton }
            if (!c.isCloudAvailable()) { showMsg("云端未配置，无法配对"); return@OutlinedButton }
            scope.launch {
                val ok = c.pairDevice(pairingCode.trim(), android.os.Build.MODEL)
                showMsg(if (ok) "配对成功" else "配对失败，请检查配对码")
                if (ok) {
                    devices.clear()
                    devices.addAll(c.discoverDevices())
                }
            }
        }) { Text("配对") }
    }
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = pairingCode,
        onValueChange = { pairingCode = it },
        label = { Text("配对码") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(12.dp))
    Text("已连接设备", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))

    LazyColumn(modifier = Modifier.height(200.dp)) {
        items(devices) { device ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(device.deviceName)
                        Text(
                            "${device.deviceType} · ${if (device.isOnline) "在线" else "离线"}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = {
                        devices.remove(device)
                        showMsg("设备已断开")
                    }) { Text("断开") }
                }
            }
        }
        if (devices.isEmpty()) {
            item {
                Text("暂无已连接设备", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ⑧ 语言语音
@Composable
private fun LanguageVoiceTab(showMsg: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedLanguage by remember { mutableIntStateOf(0) }

    val voiceManager = remember { org.koin.core.context.GlobalContext.get().get<com.jarvis.pineapple.speech.VoiceManager>() }
    val installedVoices by voiceManager.voices.collectAsState()
    val library = remember { voiceManager.getVoiceLibrary() }
    var activeVoiceId by remember { mutableStateOf<String?>(null) }

    // 加载已保存的配置
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("jarvis_language", Context.MODE_PRIVATE)
        selectedLanguage = prefs.getInt("language_index", 0)
        activeVoiceId = prefs.getString("voice_id", null)
    }

    Text("语言与语音", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    Text("声线选择（来自 VoiceManager 声线库）", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    if (library.isEmpty()) {
        Text("声线库不可用", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    library.forEach { pack ->
        val isInstalled = installedVoices.any { it.id == pack.id }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.RadioButton(
                selected = activeVoiceId == pack.id,
                onClick = {
                    if (isInstalled) {
                        voiceManager.setActiveVoice(pack.id)
                        activeVoiceId = pack.id
                        context.getSharedPreferences("jarvis_language", Context.MODE_PRIVATE)
                            .edit().putString("voice_id", pack.id).apply()
                        showMsg("已切换声线：${pack.name}")
                    } else {
                        showMsg("声线未下载，请先下载")
                    }
                }
            )
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(pack.name, fontSize = 14.sp)
                Text(
                    "${pack.language} · ${if (isInstalled) "已下载" else "未下载"}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            if (!isInstalled) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val ok = voiceManager.downloadVoice(pack) == true
                        showMsg(
                            if (ok) "已下载 ${pack.name}"
                            else voiceManager.consumeFallbackNotice()
                                ?: "下载失败（声线源不可用）"
                        )
                    }
                }) { Text("下载") }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("语言选择（规格书：支持 100+ 种语言）", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    var languageQuery by remember { mutableStateOf("") }
    OutlinedTextField(
        value = languageQuery,
        onValueChange = { languageQuery = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("搜索语言") },
        singleLine = true
    )
    Spacer(Modifier.height(8.dp))
    val languages = com.jarvis.pineapple.speech.SupportedLanguages.ALL
    val filtered = if (languageQuery.isBlank()) languages
    else languages.filter {
        it.nativeName.contains(languageQuery, ignoreCase = true) ||
            it.englishName.contains(languageQuery, ignoreCase = true) ||
            it.bcp47.contains(languageQuery, ignoreCase = true)
    }
    Text(
        "共 ${filtered.size} 种语言",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    filtered.forEach { language ->
        val index = languages.indexOf(language)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.RadioButton(
                selected = selectedLanguage == index,
                onClick = {
                    selectedLanguage = index
                    val prefs = context.getSharedPreferences("jarvis_language", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putInt("language_index", index)
                        .putString("language_bcp47", language.bcp47)
                        .apply()
                }
            )
            Text(
                "${language.nativeName}（${language.englishName}）",
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

// ⑨ 日志与记忆
@Composable
private fun LogTab(
    showMsg: (String) -> Unit,
    memorySystem: com.jarvis.pineapple.autonomous.MemorySystem = org.koin.androidx.compose.get()
) {
    val context = LocalContext.current
    Text("日志与记忆", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    // 崩溃记录（BUG-2026-1029 实机闪退取证）
    Text("崩溃记录", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
    Row {
        Button(onClick = {
            val text = runCatching { com.jarvis.pineapple.util.CrashRecorder.readAll(context) }.getOrDefault("（读取失败）")
            val crashFile = java.io.File(context.filesDir, "crash_export_${System.currentTimeMillis()}.txt")
            crashFile.writeText(text)
            showMsg(if (text == "（无崩溃记录）") "无崩溃记录" else "崩溃记录已导出: ${crashFile.absolutePath}")
        }) { Text("导出崩溃记录") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            val dir = java.io.File(context.filesDir, "crash")
            if (dir.exists()) {
                dir.listFiles()?.forEach { if (it.name.startsWith("crash_")) it.delete() }
            }
            showMsg("崩溃记录已清空")
        }) { Text("清空崩溃记录") }
    }

    Row {
        Button(onClick = {
            val logDir = java.io.File(context.filesDir, "logs")
            val latest = logDir.listFiles { f -> f.isFile }?.maxByOrNull { it.lastModified() }
            val crashText = runCatching { com.jarvis.pineapple.util.CrashRecorder.readAll(context) }.getOrDefault("")
            if (latest == null && (crashText.isBlank() || crashText == "（无崩溃记录）")) {
                showMsg("暂无日志文件")
                return@Button
            }
            val logFile = java.io.File(context.filesDir, "logs/jarvis_log_${System.currentTimeMillis()}.txt")
            logFile.parentFile?.mkdirs()
            val header = "JARVIS 日志导出\n时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}\n"
            val body = buildString {
                append(header)
                if (latest != null) {
                    append("\n########## ${latest.name} ##########\n")
                    append(runCatching { latest.readText() }.getOrDefault("（读取失败）"))
                }
                if (crashText.isNotBlank() && crashText != "（无崩溃记录）") {
                    append("\n\n")
                    append(crashText)
                }
            }
            logFile.writeText(body)
            showMsg("日志已导出: ${logFile.absolutePath}")
        }) { Text("导出日志") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            // 清空日志
            val logDir = java.io.File(context.filesDir, "logs")
            if (logDir.exists()) {
                logDir.listFiles()?.forEach { it.delete() }
            }
            showMsg("日志已清空")
        }) { Text("清空日志") }
    }

    Spacer(Modifier.height(16.dp))
    Text("记忆管理 (REQ-11)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))

    Row {
        Button(onClick = {
            val json = memorySystem.exportMemories()
            val memFile = java.io.File(context.filesDir, "memory_export_${System.currentTimeMillis()}.json")
            memFile.writeText(json)
            showMsg("记忆已导出: ${memFile.absolutePath} (${json.length} 字符)")
        }) { Text("导出记忆 (JSON)") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            memorySystem.clearAll()
            showMsg("所有记忆已清空")
        }) { Text("清空所有记忆") }
    }

    Spacer(Modifier.height(8.dp))
    // #141 核心数据迁移：从文件导入记忆
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val json = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (json.isNullOrBlank()) {
                showMsg("读取文件失败")
            } else {
                val ok = memorySystem.importMemories(json)
                showMsg(if (ok) "记忆已导入（${json.length} 字符）" else "导入失败：JSON 格式无效")
            }
        }
    }
    Button(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
        Text("导入记忆 (JSON)")
    }

    Spacer(Modifier.height(16.dp))
    Text("最近日志", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))

    // 真实日志：读取 logs 目录中最新文件，而不是伪造固定文本
    val logLines = remember {
        val logDir = java.io.File(context.filesDir, "logs")
        val latest = logDir.listFiles { f -> f.isFile }?.maxByOrNull { it.lastModified() }
        latest?.readLines()?.takeLast(20) ?: emptyList()
    }

    Card(
        modifier = Modifier.fillMaxWidth().height(200.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        LazyColumn(modifier = Modifier.padding(12.dp)) {
            if (logLines.isEmpty()) {
                item { Text("暂无日志（尚未写入日志文件）", fontSize = 12.sp) }
            } else {
                items(logLines) { log ->
                    Text(log, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

// ⑩ 通用
@Composable
private fun GeneralTab(showMsg: (String) -> Unit) {
    val context = LocalContext.current
    var voiceWake by remember { mutableStateOf(true) }
    var powerWake by remember { mutableStateOf(true) }
    var shakeWake by remember { mutableStateOf(true) }
    var bluetoothWake by remember { mutableStateOf(true) }
    var cloudSync by remember { mutableStateOf(false) }
    var autoDownloadModels by remember { mutableStateOf(false) }

    // 加载已保存的配置
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("jarvis_general", Context.MODE_PRIVATE)
        voiceWake = prefs.getBoolean("voice_wake", true)
        powerWake = prefs.getBoolean("power_wake", true)
        shakeWake = prefs.getBoolean("shake_wake", true)
        bluetoothWake = prefs.getBoolean("bluetooth_wake", true)
        cloudSync = prefs.getBoolean("cloud_sync", false)
        autoDownloadModels = prefs.getBoolean("auto_download_models", false)
    }

    Text("通用设置", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Button(onClick = {
        val intents = listOf(
            android.content.Intent("android.settings.VOICE_INPUT_SETTINGS"),
            android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        )
        val launched = intents.any { intent ->
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.isSuccess
        }
        showMsg(if (launched) "请在系统设置中将 JARVIS 设为默认数字助手" else "无法打开系统默认助手设置")
    }) { Text("一键设为默认 AI 助手") }
    Spacer(Modifier.height(8.dp))

    SettingSwitch("语音唤醒", voiceWake, {
        voiceWake = it
        applyWakeSource(context, com.jarvis.pineapple.wake.WakeSource.VOICE, it)
        saveGeneralPrefs(context, voiceWake, powerWake, shakeWake, bluetoothWake, cloudSync, autoDownloadModels)
    })
    SettingSwitch("电源键唤醒", powerWake, {
        powerWake = it
        applyWakeSource(context, com.jarvis.pineapple.wake.WakeSource.POWER_BUTTON, it)
        saveGeneralPrefs(context, voiceWake, powerWake, shakeWake, bluetoothWake, cloudSync, autoDownloadModels)
    })
    SettingSwitch("摇一摇唤醒", shakeWake, {
        shakeWake = it
        applyWakeSource(context, com.jarvis.pineapple.wake.WakeSource.SHAKE, it)
        saveGeneralPrefs(context, voiceWake, powerWake, shakeWake, bluetoothWake, cloudSync, autoDownloadModels)
    })
    SettingSwitch("蓝牙按键唤醒", bluetoothWake, {
        bluetoothWake = it
        applyWakeSource(context, com.jarvis.pineapple.wake.WakeSource.BLUETOOTH, it)
        saveGeneralPrefs(context, voiceWake, powerWake, shakeWake, bluetoothWake, cloudSync, autoDownloadModels)
    })

    Spacer(Modifier.height(16.dp))
    Text("云端同步", style = MaterialTheme.typography.bodyMedium)
    val cloud = remember { runCatching { org.koin.core.context.GlobalContext.get().get<com.jarvis.pineapple.cloud.CloudSyncManager>() }.getOrNull() }
    SettingSwitch("启用云端同步", cloudSync, {
        cloudSync = it
        cloud?.setCloudEnabled(it)
        if (it && cloud?.isCloudAvailable() == false) {
            showMsg("未配置云端服务地址，云端能力不可用（本地功能不受影响）")
        } else {
            showMsg(if (it) "云端同步已开启" else "云端同步已关闭")
        }
        saveGeneralPrefs(context, voiceWake, powerWake, shakeWake, bluetoothWake, cloudSync, autoDownloadModels)
    })

    Spacer(Modifier.height(16.dp))
    Text("模型", style = MaterialTheme.typography.bodyMedium)
    SettingSwitch("自动下载缺失模型", autoDownloadModels, {
        autoDownloadModels = it
        saveGeneralPrefs(context, voiceWake, powerWake, shakeWake, bluetoothWake, cloudSync, autoDownloadModels)
    })

    Spacer(Modifier.height(16.dp))
    Text("界面自定义", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    val uiRepo = remember {
        runCatching {
            org.koin.core.context.GlobalContext.get().get<com.jarvis.pineapple.ui.theme.UiCustomizationRepository>()
        }.getOrElse { com.jarvis.pineapple.ui.theme.UiCustomizationRepository(context) }
    }
    val currentColor by uiRepo.themeColor.collectAsState()
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            uiRepo.setBackgroundUri(uri.toString())
            showMsg("背景图已更新")
        }
    }
    Button(onClick = { bgPicker.launch(arrayOf("image/*")) }) { Text("更换背景图") }
    Spacer(Modifier.height(8.dp))
    Text("主题颜色", fontSize = 13.sp)
    com.jarvis.pineapple.ui.theme.UiCustomizationRepository.PRESET_COLORS.forEach { (name, argb) ->
        val selected = currentColor == argb
        OutlinedButton(onClick = {
            uiRepo.setThemeColor(argb)
            showMsg("主题色已设为$name")
        }) { Text(if (selected) "当前：$name" else name) }
        Spacer(Modifier.height(4.dp))
    }
    OutlinedButton(onClick = {
        uiRepo.clearThemeColor()
        showMsg("已恢复默认主题色")
    }) { Text("恢复默认主题色") }
}

/** 将开关状态同步到真实的 WakeEngine（唤醒源启停）。 */
private fun applyWakeSource(
    context: android.content.Context,
    source: com.jarvis.pineapple.wake.WakeSource,
    enabled: Boolean
) {
    runCatching {
        org.koin.core.context.GlobalContext.get().get<com.jarvis.pineapple.wake.WakeEngine>()
            .setEnabled(source, enabled)
    }
}

private fun saveGeneralPrefs(
    context: android.content.Context,
    voiceWake: Boolean,
    powerWake: Boolean,
    shakeWake: Boolean,
    bluetoothWake: Boolean,
    cloudSync: Boolean,
    autoDownloadModels: Boolean
) {
    val prefs = context.getSharedPreferences("jarvis_general", Context.MODE_PRIVATE)
    prefs.edit()
        .putBoolean("voice_wake", voiceWake)
        .putBoolean("power_wake", powerWake)
        .putBoolean("shake_wake", shakeWake)
        .putBoolean("bluetooth_wake", bluetoothWake)
        .putBoolean("cloud_sync", cloudSync)
        .putBoolean("auto_download_models", autoDownloadModels)
        .apply()
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

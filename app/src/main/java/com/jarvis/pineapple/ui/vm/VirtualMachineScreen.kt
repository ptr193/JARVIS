package com.jarvis.pineapple.ui.vm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.pineapple.vm.DownloadStatus
import com.jarvis.pineapple.vm.HavenVmManager
import com.jarvis.pineapple.vm.PackageInfo
import com.jarvis.pineapple.vm.PackageOperationResult
import com.jarvis.pineapple.vm.ProotManager
import com.jarvis.pineapple.vm.VmDistroManager
import com.jarvis.pineapple.vm.VmFile
import com.jarvis.pineapple.vm.VmFileManager
import com.jarvis.pineapple.vm.VmImageImporter
import com.jarvis.pineapple.vm.VmPackageManager
import com.jarvis.pineapple.vm.VmTerminal
import kotlinx.coroutines.launch
import org.koin.androidx.compose.get
import java.io.File
import java.io.FileOutputStream

/**
 * 虚拟机界面。
 *
 * 所有功能均连接到真实的 PRoot 后端：
 * - 发行版：真实下载 / SHA-256 校验 / 解压 aarch64 rootfs
 * - 虚拟机：创建 / 启动 / 停止 / 删除
 * - 终端：真实命令执行
 * - 软件包：真实包管理器操作
 * - 文件：直接浏览 rootfs 文件系统
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualMachineScreen(
    onBack: () -> Unit,
    prootManager: ProotManager = get(),
    distroManager: VmDistroManager = get(),
    havenVmManager: HavenVmManager = get(),
    vmImageImporter: VmImageImporter = get()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val showMsg: (String) -> Unit = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }

    val prootReady by prootManager.isReady.collectAsState()
    val distros by distroManager.distros.collectAsState()
    val progressMap by distroManager.downloadProgress.collectAsState()
    val vms by havenVmManager.vms.collectAsState()

    var prootVersion by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var activeVmId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(prootReady) {
        if (prootReady) prootVersion = prootManager.getProotVersion()
    }

    val tabs = listOf("发行版", "虚拟机", "终端", "软件包", "文件")

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            scope.launch {
                showMsg("正在复制镜像文件...")
                val localFile = copyUriToLocalFile(context, selectedUri)
                if (localFile == null) {
                    showMsg("文件复制失败")
                    return@launch
                }
                showMsg("正在导入镜像: ${localFile.name}...")
                when (val result = vmImageImporter.importImage(localFile.absolutePath)) {
                    is com.jarvis.pineapple.vm.ImportResult.Success -> {
                        showMsg(result.message)
                        distroManager.refreshDownloadState()
                    }
                    is com.jarvis.pineapple.vm.ImportResult.Failure -> showMsg("导入失败: ${result.error}")
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "虚拟机",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            ProotStatusCard(
                ready = prootReady,
                version = prootVersion
            )

            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { i, title ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = { Text(title, fontSize = 13.sp) }
                    )
                }
            }

            when (selectedTab) {
                0 -> DistroTab(
                    distros = distros,
                    progressProvider = { progressMap[it] },
                    onDownload = { id ->
                        scope.launch {
                            showMsg("开始下载发行版...")
                            val ok = distroManager.downloadDistro(id)
                            showMsg(if (ok) "发行版下载完成" else "发行版下载失败")
                        }
                    },
                    onDelete = { id ->
                        distroManager.deleteDistro(id)
                        showMsg("已删除发行版")
                    },
                    onCreateVm = { id ->
                        scope.launch {
                            val result = havenVmManager.createVm("", id)
                            val vm = result.vm
                            if (vm != null) {
                                activeVmId = vm.id
                                selectedTab = 2
                                showMsg("虚拟机 '${vm.name}' 已创建")
                            } else {
                                showMsg(result.error ?: "创建虚拟机失败")
                            }
                        }
                    },
                    onImport = { importLauncher.launch(arrayOf("*/*")) }
                )
                1 -> VmTab(
                    vms = vms,
                    activeVmId = activeVmId,
                    onSelect = { activeVmId = it },
                    onStart = { id ->
                        scope.launch {
                            showMsg("正在启动虚拟机...")
                            val ok = havenVmManager.startVm(id)
                            showMsg(if (ok) "虚拟机已启动" else "虚拟机启动失败")
                        }
                    },
                    onStop = { id ->
                        havenVmManager.stopVm(id)
                        showMsg("虚拟机已停止")
                    },
                    onDelete = { id ->
                        scope.launch {
                            havenVmManager.deleteVm(id)
                            if (activeVmId == id) activeVmId = null
                            showMsg("虚拟机已删除")
                        }
                    }
                )
                2 -> TerminalTab(
                    vm = vms.firstOrNull { it.id == activeVmId },
                    prootManager = prootManager,
                    showMsg = showMsg
                )
                3 -> PackageTab(
                    vm = vms.firstOrNull { it.id == activeVmId },
                    prootManager = prootManager,
                    showMsg = showMsg
                )
                4 -> FileTab(
                    vm = vms.firstOrNull { it.id == activeVmId },
                    showMsg = showMsg
                )
            }
        }
    }
}

/**
 * 将 SAF Uri 内容复制到应用私有目录。
 */
private fun copyUriToLocalFile(context: Context, uri: Uri): File? {
    return try {
        val contentResolver = context.contentResolver
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val displayName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: "imported_image"
        val extension = if (displayName.contains(".")) displayName.substringAfterLast(".") else "img"
        val localFile = File(context.filesDir, "vm_images/${System.currentTimeMillis()}.$extension")
        localFile.parentFile?.mkdirs()
        inputStream.use { input ->
            FileOutputStream(localFile).use { output -> input.copyTo(output) }
        }
        localFile
    } catch (e: Exception) {
        android.util.Log.e("VirtualMachineScreen", "copyUriToLocalFile failed", e)
        null
    }
}

@Composable
private fun ProotStatusCard(ready: Boolean, version: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (ready) "PRoot 已就绪" else "PRoot 未安装",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    if (ready) version.ifBlank { "PRoot" } else "PRoot 二进制未随包或缓存缺失",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DistroTab(
    distros: List<com.jarvis.pineapple.vm.DistroInfo>,
    progressProvider: (String) -> com.jarvis.pineapple.vm.DownloadProgress?,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCreateVm: (String) -> Unit,
    onImport: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(distros, key = { it.id }) { distro ->
                val progress = progressProvider(distro.id)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${distro.name} ${distro.version}", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${distro.architecture} · ${formatSize(distro.sizeBytes)} · ${distro.packageManager}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            distro.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (distro.unavailableReason != null) {
                            Text(
                                distro.unavailableReason,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        if (progress != null && (progress.status == DownloadStatus.DOWNLOADING || progress.status == DownloadStatus.VERIFYING)) {
                            LinearProgressIndicator(
                                progress = { progress.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                if (progress.status == DownloadStatus.VERIFYING) "校验/解压中..." else "下载中 ${(progress.progress * 100).toInt()}%",
                                fontSize = 12.sp
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                if (distro.isDownloaded) {
                                    TextButton(onClick = { onCreateVm(distro.id) }) { Text("创建虚拟机") }
                                    TextButton(onClick = { onDelete(distro.id) }) { Text("删除") }
                                } else if (distro.unavailableReason != null) {
                                    Text(distro.unavailableReason, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                } else {
                                    Button(onClick = { onDownload(distro.id) }) {
                                        Icon(Icons.Filled.Download, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("下载")
                                    }
                                }
                            }
                            if (progress != null && progress.status == DownloadStatus.FAILED) {
                                Text("失败: ${progress.errorMessage}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            OutlinedButton(onClick = onImport) { Text("导入镜像文件 (tar.gz / qcow2 / iso 等)") }
        }
    }
}

@Composable
private fun VmTab(
    vms: List<HavenVmManager.HavenVm>,
    activeVmId: String?,
    onSelect: (String) -> Unit,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    if (vms.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无虚拟机，请在“发行版”中下载并创建", fontSize = 13.sp)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(vms, key = { it.id }) { vm ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = activeVmId == vm.id, onClick = { onSelect(vm.id) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(vm.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${vm.distroName} · ${stateText(vm.state)}",
                            fontSize = 12.sp,
                            color = if (vm.state == HavenVmManager.HavenVmState.RUNNING)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (vm.state == HavenVmManager.HavenVmState.RUNNING) {
                        IconButton(onClick = { onStop(vm.id) }) {
                            Icon(Icons.Filled.Stop, contentDescription = "停止")
                        }
                    } else {
                        IconButton(onClick = { onStart(vm.id) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "启动")
                        }
                    }
                    IconButton(onClick = { onDelete(vm.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
            }
        }
    }
}

private fun stateText(state: HavenVmManager.HavenVmState): String = when (state) {
    HavenVmManager.HavenVmState.STOPPED -> "已停止"
    HavenVmManager.HavenVmState.STARTING -> "启动中"
    HavenVmManager.HavenVmState.RUNNING -> "运行中"
    HavenVmManager.HavenVmState.STOPPING -> "停止中"
    HavenVmManager.HavenVmState.ERROR -> "错误"
}

@Composable
private fun TerminalTab(vm: HavenVmManager.HavenVm?, prootManager: ProotManager, showMsg: (String) -> Unit) {
    if (vm == null) {
        EmptyHint("请先在“虚拟机”中选择一个虚拟机")
        return
    }

    val scope = rememberCoroutineScope()
    val terminal = remember(vm.id) {
        VmTerminal(vm.id, vm.rootfsPath, prootManager).also { it.connect() }
    }
    val output by terminal.output.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text("${vm.name} · ${vm.rootfsPath}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = false
        ) {
            items(output) { line ->
                Text(line, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入命令...") },
                singleLine = true
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = {
                val command = input
                input = ""
                if (command.isNotBlank()) {
                    scope.launch { terminal.executeCommand(command) }
                }
            }) { Text("执行") }
        }
    }
}

@Composable
private fun PackageTab(vm: HavenVmManager.HavenVm?, prootManager: ProotManager, showMsg: (String) -> Unit) {
    if (vm == null) {
        EmptyHint("请先在“虚拟机”中选择一个虚拟机")
        return
    }
    val scope = rememberCoroutineScope()
    val manager = remember(vm.id) {
        VmPackageManager(vm.id, vm.rootfsPath, prootManager, vm.packageManager)
    }
    val packages by manager.packages.collectAsState()
    val isOperating by manager.isOperating.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(vm.id) {
        manager.refreshPackageList()
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text("包管理器: ${vm.packageManager}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("软件包名") },
                singleLine = true
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = {
                val pkg = query
                if (pkg.isNotBlank()) {
                    scope.launch {
                        when (val r = manager.installPackage(pkg)) {
                            is PackageOperationResult.Success -> showMsg(r.message)
                            is PackageOperationResult.Failure -> showMsg(r.error)
                            is PackageOperationResult.NeedRoot -> showMsg(r.message)
                        }
                    }
                }
            }, enabled = !isOperating) { Text("安装") }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    manager.refreshPackageList()
                    showMsg("已刷新已安装列表")
                }
            }, enabled = !isOperating) { Text("刷新已安装") }
            OutlinedButton(onClick = {
                scope.launch {
                    showMsg("正在更新所有软件包...")
                    val r = manager.updateAllPackages()
                    showMsg(if (r is PackageOperationResult.Success) r.message else "更新失败")
                }
            }, enabled = !isOperating) { Text("全部更新") }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(packages, key = { it.name }) { pkg ->
                PackageRow(pkg, isOperating) {
                    scope.launch {
                        val r = manager.uninstallPackage(pkg.name)
                        if (r is PackageOperationResult.Success) showMsg(r.message)
                    }
                }
            }
        }
    }
}

@Composable
private fun PackageRow(pkg: PackageInfo, enabled: Boolean, onUninstall: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(pkg.name, modifier = Modifier.weight(1f), fontSize = 13.sp)
        if (pkg.installed) {
            TextButton(onClick = onUninstall, enabled = enabled) { Text("卸载") }
        }
    }
}

@Composable
private fun FileTab(vm: HavenVmManager.HavenVm?, showMsg: (String) -> Unit) {
    if (vm == null) {
        EmptyHint("请先在“虚拟机”中选择一个虚拟机")
        return
    }
    val manager = remember(vm.id) { VmFileManager(vm.id, vm.rootfsPath) }
    val currentPath by manager.currentPath.collectAsState()
    var files by remember { mutableStateOf<List<VmFile>>(emptyList()) }

    LaunchedEffect(vm.id) {
        files = manager.listFiles("/")
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { manager.goBack(); files = manager.files.value }) { Text("↑ 上级") }
            Text(currentPath, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(files, key = { it.path }) { file ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        (if (file.isDirectory) "📁 " else "📄 ") + file.name,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp
                    )
                    if (file.isDirectory) {
                        TextButton(onClick = {
                            manager.listFiles(file.path)
                            files = manager.files.value
                        }) { Text("打开") }
                    } else {
                        Text(formatSize(file.sizeBytes), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

package com.jarvis.pineapple.ui.vm

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.pineapple.vm.DownloadStatus
import com.jarvis.pineapple.vm.HavenVmManager
import com.jarvis.pineapple.vm.PackageInfo
import com.jarvis.pineapple.vm.PackageOperationResult
import com.jarvis.pineapple.vm.ProotManager
import com.jarvis.pineapple.vm.SnapshotResult
import com.jarvis.pineapple.vm.VmDistroManager
import com.jarvis.pineapple.vm.VmDesktopManager
import com.jarvis.pineapple.vm.VmFile
import com.jarvis.pineapple.vm.VmFileManager
import com.jarvis.pineapple.vm.VmImageImporter
import com.jarvis.pineapple.vm.VmLogManager
import com.jarvis.pineapple.vm.VmPackageManager
import com.jarvis.pineapple.vm.VmResourceMonitor
import com.jarvis.pineapple.vm.VmSnapshotManager
import com.jarvis.pineapple.vm.VmTerminal
import com.jarvis.pineapple.vm.VncClient
import com.jarvis.pineapple.vm.LogLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
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
    vmImageImporter: VmImageImporter = get(),
    vmDesktopManager: VmDesktopManager = get()
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

    // 每个虚拟机一份日志管理器，供终端/快照/生命周期操作统一记录。
    val logManagers = remember { mutableMapOf<String, VmLogManager>() }
    val loggerFor: (String?) -> VmLogManager? = { id ->
        id?.let { logManagers.getOrPut(it) { VmLogManager(it) } }
    }

    LaunchedEffect(prootReady) {
        if (prootReady) prootVersion = prootManager.getProotVersion()
    }

    val tabs = listOf("发行版", "虚拟机", "终端", "软件包", "文件", "快照", "监控", "日志", "桌面")

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

            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
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
                        val deleted = distroManager.deleteDistro(id)
                        showMsg(if (deleted) "已删除发行版" else "发行版删除失败")
                    },
                    onCreateVm = { id ->
                        scope.launch {
                            val result = havenVmManager.createVm("", id)
                            val vm = result.vm
                            if (vm != null) {
                                activeVmId = vm.id
                                selectedTab = 2
                                loggerFor(vm.id)?.addLog(LogLevel.INFO, "已创建虚拟机 '${vm.name}'", "lifecycle")
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
                            loggerFor(id)?.addLog(
                                if (ok) LogLevel.INFO else LogLevel.ERROR,
                                if (ok) "虚拟机启动成功" else "虚拟机启动失败",
                                "lifecycle"
                            )
                            showMsg(if (ok) "虚拟机已启动" else "虚拟机启动失败")
                        }
                    },
                    onStop = { id ->
                        scope.launch {
                            val ok = havenVmManager.stopVm(id)
                            loggerFor(id)?.addLog(
                                if (ok) LogLevel.INFO else LogLevel.ERROR,
                                if (ok) "虚拟机停止成功" else "虚拟机停止失败",
                                "lifecycle"
                            )
                            showMsg(if (ok) "虚拟机已停止" else "虚拟机停止失败")
                        }
                    },
                    onDelete = { id ->
                        scope.launch {
                            val ok = havenVmManager.deleteVm(id)
                            loggerFor(id)?.addLog(
                                if (ok) LogLevel.INFO else LogLevel.ERROR,
                                if (ok) "虚拟机已删除" else "虚拟机删除失败",
                                "lifecycle"
                            )
                            if (ok && activeVmId == id) activeVmId = null
                            showMsg(if (ok) "虚拟机已删除" else "虚拟机删除失败")
                        }
                    },
                    onInstallDesktop = { id ->
                        scope.launch {
                            showMsg("正在安装桌面环境（体积较大，请耐心等待）...")
                            val ok = havenVmManager.installDesktopEnvironment(id)
                            loggerFor(id)?.addLog(
                                if (ok) LogLevel.INFO else LogLevel.ERROR,
                                if (ok) "桌面环境安装成功" else "桌面环境安装失败",
                                "desktop"
                            )
                            showMsg(if (ok) "桌面环境已安装" else "桌面环境安装失败")
                        }
                    }
                )
                2 -> TerminalTab(
                    vm = vms.firstOrNull { it.id == activeVmId },
                    prootManager = prootManager,
                    showMsg = showMsg,
                    logManager = loggerFor(activeVmId)
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
                5 -> SnapshotTab(
                    vm = vms.firstOrNull { it.id == activeVmId },
                    context = context,
                    showMsg = showMsg,
                    logManager = loggerFor(activeVmId)
                )
                6 -> MonitorTab(vm = vms.firstOrNull { it.id == activeVmId })
                7 -> LogTab(logManager = loggerFor(activeVmId))
                8 -> DesktopTab(
                    vm = vms.firstOrNull { it.id == activeVmId },
                    desktop = vmDesktopManager,
                    logManager = loggerFor(activeVmId),
                    showMsg = showMsg
                )
            }
        }
    }
}

/**
 * 虚拟机桌面页：内置 RFB(VNC) 客户端，显示并操作 PRoot 内的 XFCE 桌面。
 * 桌面由 Xvfb + x11vnc 提供，用户可通过触摸/键盘操作，AI 通过终端操作同一虚拟机。
 */
@Composable
private fun DesktopTab(
    vm: HavenVmManager.HavenVm?,
    desktop: VmDesktopManager,
    logManager: VmLogManager?,
    showMsg: (String) -> Unit
) {
    if (vm == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("请先在“虚拟机”页选择一台虚拟机", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val scope = rememberCoroutineScope()
    var running by remember(vm.id) { mutableStateOf(desktop.isRunning(vm.id)) }
    var port by remember(vm.id) { mutableIntStateOf(desktop.portFor(vm.id)) }
    var status by remember(vm.id) { mutableStateOf(if (running) "桌面进程运行中" else "桌面未启动") }
    var busy by remember(vm.id) { mutableStateOf(false) }
    var frame by remember(vm.id) { mutableStateOf<ImageBitmap?>(null) }
    var frameWidth by remember(vm.id) { mutableIntStateOf(0) }
    var frameHeight by remember(vm.id) { mutableIntStateOf(0) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var textInput by remember { mutableStateOf("") }
    var connectionKey by remember(vm.id) { mutableIntStateOf(0) }
    var lastPointer by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val client = remember(vm.id) {
        VncClient(object : VncClient.Listener {
            override fun onConnected(width: Int, height: Int) {
                scope.launch {
                    frameWidth = width
                    frameHeight = height
                    status = "已连接 ${width}x${height}"
                }
            }

            override fun onFrame(pixels: IntArray, width: Int, height: Int) {
                val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                    .asImageBitmap()
                scope.launch {
                    frame = bitmap
                    frameWidth = width
                    frameHeight = height
                }
            }

            override fun onDisconnected(reason: String) {
                scope.launch {
                    status = reason
                    running = desktop.isRunning(vm.id)
                }
            }
        })
    }

    DisposableEffect(vm.id) {
        onDispose { client.disconnect() }
    }

    LaunchedEffect(connectionKey, port) {
        if (connectionKey > 0) {
            status = "连接 127.0.0.1:$port ..."
            client.run("127.0.0.1", port)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        status = "正在安装桌面环境（需联网，可能较久）..."
                        val ok = desktop.installDesktop(vm.id)
                        busy = false
                        status = if (ok) "桌面环境安装完成" else "桌面环境安装失败，请检查网络/包管理器"
                        if (ok) logManager?.addLog(LogLevel.INFO, status, "Desktop")
                        else logManager?.addLog(LogLevel.ERROR, status, "Desktop")
                        showMsg(status)
                    }
                },
                enabled = !busy
            ) { Text("安装桌面环境") }

            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        status = "正在启动桌面..."
                        val result = desktop.start(vm.id)
                        busy = false
                        if (result.running) {
                            running = true
                            port = result.port
                            frame = null
                            status = "桌面已启动，正在连接 127.0.0.1:${result.port}"
                            logManager?.addLog(LogLevel.INFO, status, "Desktop")
                            connectionKey++
                        } else {
                            running = false
                            status = result.error ?: "桌面启动失败"
                            logManager?.addLog(LogLevel.ERROR, status, "Desktop")
                            showMsg(status)
                        }
                    }
                },
                enabled = !busy
            ) { Text("启动桌面") }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        client.disconnect()
                        val ok = desktop.stop(vm.id)
                        busy = false
                        if (ok) {
                            running = false
                            frame = null
                            status = "桌面已停止"
                            logManager?.addLog(LogLevel.INFO, status, "Desktop")
                        } else {
                            status = "桌面进程未能正常停止"
                            logManager?.addLog(LogLevel.ERROR, status, "Desktop")
                        }
                        showMsg(status)
                    }
                },
                enabled = !busy
            ) { Text("停止桌面") }
        }

        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val image = frame
        if (image == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(320.dp).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text(if (running) "等待桌面画面…" else "桌面未连接", color = Color.White)
            }
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(Color.Black)
                    .onSizeChanged { viewSize = it }
                    .pointerInput(frameWidth, frameHeight, viewSize) {
                        detectTapGestures { offset ->
                            mapToFrame(offset, viewSize, frameWidth, frameHeight)?.let { point ->
                                client.pointer(point.first, point.second, 1)
                                client.pointer(point.first, point.second, 0)
                            }
                        }
                    }
                    .pointerInput(frameWidth, frameHeight, viewSize) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                mapToFrame(offset, viewSize, frameWidth, frameHeight)?.let { point ->
                                    lastPointer = point
                                    client.pointer(point.first, point.second, 1)
                                }
                            },
                            onDrag = { change, _ ->
                                mapToFrame(change.position, viewSize, frameWidth, frameHeight)?.let { point ->
                                    lastPointer = point
                                    client.pointer(point.first, point.second, 1)
                                }
                            },
                            onDragEnd = {
                                lastPointer?.let { client.pointer(it.first, it.second, 0) }
                                lastPointer = null
                            }
                        )
                    }
            ) {
                val scale = min(
                    size.width / image.width,
                    size.height / image.height
                )
                val drawWidth = image.width * scale
                val drawHeight = image.height * scale
                drawImage(
                    image = image,
                    dstOffset = IntOffset(
                        ((size.width - drawWidth) / 2f).toInt(),
                        ((size.height - drawHeight) / 2f).toInt()
                    ),
                    dstSize = IntSize(drawWidth.toInt(), drawHeight.toInt())
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                label = { Text("键盘输入（ASCII）") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                client.sendText(textInput)
                textInput = ""
            }) { Text("发送") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                client.key(0xFF0D, true)
                client.key(0xFF0D, false)
            }) { Text("回车") }
            OutlinedButton(onClick = {
                client.key(0xFF1B, true)
                client.key(0xFF1B, false)
            }) { Text("Esc") }
            OutlinedButton(onClick = {
                client.key(0xFF09, true)
                client.key(0xFF09, false)
            }) { Text("Tab") }
            OutlinedButton(onClick = {
                scope.launch {
                    val ok = desktop.launchBrowser(vm.id)
                    val msg = if (ok) "已在桌面启动浏览器" else "启动浏览器失败：桌面未运行或未安装浏览器"
                    if (ok) logManager?.addLog(LogLevel.INFO, msg, "Desktop")
                    else logManager?.addLog(LogLevel.ERROR, msg, "Desktop")
                    showMsg(msg)
                }
            }) { Text("打开浏览器") }
        }

        Text(
            "提示：桌面由 Xvfb + x11vnc 提供，AI 可在同一虚拟机执行命令；浏览器需在桌面安装后再打开。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 将画布触摸点换算为 VNC 帧缓冲坐标；落在黑边时返回 null。 */
private fun mapToFrame(
    offset: Offset,
    viewSize: IntSize,
    frameWidth: Int,
    frameHeight: Int
): Pair<Int, Int>? {
    if (viewSize.width <= 0 || viewSize.height <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
    val scale = min(
        viewSize.width.toFloat() / frameWidth,
        viewSize.height.toFloat() / frameHeight
    )
    if (scale <= 0f) return null
    val drawWidth = frameWidth * scale
    val drawHeight = frameHeight * scale
    val left = (viewSize.width - drawWidth) / 2f
    val top = (viewSize.height - drawHeight) / 2f
    val x = ((offset.x - left) / scale).toInt()
    val y = ((offset.y - top) / scale).toInt()
    if (x !in 0 until frameWidth || y !in 0 until frameHeight) return null
    return x to y
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
    onDelete: (String) -> Unit,
    onInstallDesktop: (String) -> Unit
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
                Column {
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
                    if (activeVmId == vm.id) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { onInstallDesktop(vm.id) },
                                enabled = vm.state == HavenVmManager.HavenVmState.RUNNING
                            ) { Text("安装桌面环境", fontSize = 12.sp) }
                        }
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
private fun TerminalTab(
    vm: HavenVmManager.HavenVm?,
    prootManager: ProotManager,
    showMsg: (String) -> Unit,
    logManager: VmLogManager?
) {
    if (vm == null) {
        EmptyHint("请先在“虚拟机”中选择一个虚拟机")
        return
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val useRoot = remember {
        context.getSharedPreferences("jarvis_vm_config", Context.MODE_PRIVATE)
            .getBoolean("enable_root", true)
    }
    val terminal = remember(vm.id) {
        VmTerminal(vm.id, vm.rootfsPath, prootManager, useRoot).also { it.connect() }
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
                    scope.launch {
                        val result = terminal.executeCommand(command)
                        logManager?.addLog(
                            if (result.exitCode == 0) LogLevel.INFO else LogLevel.ERROR,
                            "命令 [$command] 退出码 ${result.exitCode}",
                            "terminal"
                        )
                    }
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
    val context = LocalContext.current
    val useRoot = remember {
        context.getSharedPreferences("jarvis_vm_config", Context.MODE_PRIVATE)
            .getBoolean("enable_root", true)
    }
    val manager = remember(vm.id) {
        VmPackageManager(vm.id, vm.rootfsPath, prootManager, vm.packageManager, useRoot)
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
                    val refreshed = manager.refreshPackageList()
                    showMsg(if (refreshed) "已刷新已安装列表" else "刷新失败，请检查虚拟机与包管理器")
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
    val manager = remember(vm.id) { VmFileManager(vm.rootfsPath) }
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
private fun SnapshotTab(
    vm: HavenVmManager.HavenVm?,
    context: Context,
    showMsg: (String) -> Unit,
    logManager: VmLogManager?
) {
    if (vm == null) {
        EmptyHint("请先在“虚拟机”中选择一个虚拟机")
        return
    }
    val scope = rememberCoroutineScope()
    val managerResult = remember(vm.id) {
        runCatching {
            VmSnapshotManager(vm.id, vm.rootfsPath, File(context.filesDir, "vm_snapshots/${vm.id}"))
        }
    }
    val manager = managerResult.getOrNull()
    if (manager == null) {
        EmptyHint("快照功能不可用：${managerResult.exceptionOrNull()?.message ?: "初始化失败"}")
        return
    }
    val snapshots by manager.snapshots.collectAsState()
    val operating by manager.isOperating.collectAsState()
    var name by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("快照名称（可选）") },
                singleLine = true
            )
            Spacer(Modifier.width(6.dp))
            Button(
                enabled = !operating,
                onClick = {
                    val snapshotName = name.ifBlank { "快照 ${snapshots.size + 1}" }
                    scope.launch {
                        when (val r = manager.createSnapshot(snapshotName)) {
                            is SnapshotResult.Success -> {
                                showMsg(r.message)
                                logManager?.addLog(LogLevel.INFO, r.message, "snapshot")
                            }
                            is SnapshotResult.Failure -> {
                                showMsg(r.error)
                                logManager?.addLog(LogLevel.ERROR, r.error, "snapshot")
                            }
                        }
                        name = ""
                    }
                }
            ) { Text(if (operating) "处理中..." else "创建快照") }
        }
        Spacer(Modifier.height(8.dp))
        if (snapshots.isEmpty()) {
            EmptyHint("暂无快照")
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(snapshots, key = { it.id }) { snapshot ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(snapshot.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                buildString {
                                    append(formatTime(snapshot.createdAt))
                                    append(" · ")
                                    append(formatSize(snapshot.sizeBytes))
                                    if (snapshot.isCurrent) append(" · 当前")
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (snapshot.description.isNotBlank()) {
                                Text(snapshot.description, fontSize = 11.sp)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(enabled = !operating, onClick = {
                                    scope.launch {
                                        when (val r = manager.rollbackToSnapshot(snapshot.id)) {
                                            is SnapshotResult.Success -> {
                                                showMsg(r.message)
                                                logManager?.addLog(LogLevel.INFO, r.message, "snapshot")
                                            }
                                            is SnapshotResult.Failure -> {
                                                showMsg(r.error)
                                                logManager?.addLog(LogLevel.ERROR, r.error, "snapshot")
                                            }
                                        }
                                    }
                                }) { Text("回滚") }
                                TextButton(enabled = !operating, onClick = {
                                    when (val r = manager.deleteSnapshot(snapshot.id)) {
                                        is SnapshotResult.Success -> {
                                            showMsg(r.message)
                                            logManager?.addLog(LogLevel.INFO, r.message, "snapshot")
                                        }
                                        is SnapshotResult.Failure -> {
                                            showMsg(r.error)
                                            logManager?.addLog(LogLevel.ERROR, r.error, "snapshot")
                                        }
                                    }
                                }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorTab(vm: HavenVmManager.HavenVm?) {
    if (vm == null) {
        EmptyHint("请先在“虚拟机”中选择一个虚拟机")
        return
    }
    val monitor = remember(vm.id) { VmResourceMonitor(vm.id, vm.rootfsPath) }
    val usage by monitor.usage.collectAsState()
    val alerts by monitor.alerts.collectAsState()

    LaunchedEffect(vm.id) {
        while (true) {
            monitor.refresh()
            delay(2000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        MetricRow("CPU 使用率", "${usage.cpuUsagePercent.toInt()}%", usage.cpuUsagePercent / 100f)
        MetricRow(
            "内存",
            "${usage.memoryUsedMb} / ${usage.memoryTotalMb} MB",
            usage.memoryUsagePercent / 100f
        )
        MetricRow(
            "磁盘（rootfs）",
            "%.2f / %.2f GB".format(usage.diskUsedGb, usage.diskTotalGb),
            usage.diskUsagePercent / 100f
        )
        Text(
            "网络 入 %.1f / 出 %.1f KB/s".format(usage.networkInKbps, usage.networkOutKbps),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        if (alerts.isEmpty()) {
            Text("无资源告警", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("资源告警", style = MaterialTheme.typography.titleSmall)
            alerts.forEach { alert ->
                Text("· ${alert.message}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, progress: Float) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
            Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        )
    }
}

@Composable
private fun LogTab(logManager: VmLogManager?) {
    if (logManager == null) {
        EmptyHint("请先在“虚拟机”中选择一个虚拟机")
        return
    }
    val logs by logManager.logs.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("日志（${logs.size} 条）", modifier = Modifier.weight(1f), fontSize = 13.sp)
            TextButton(onClick = { logManager.clearLogs() }) { Text("清空") }
        }
        if (logs.isEmpty()) {
            EmptyHint("暂无日志，操作虚拟机后会在此记录")
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(logs.asReversed(), key = { it.id }) { entry ->
                    Text(
                        "${formatTime(entry.timestamp)} [${entry.level}] ${entry.source}: ${entry.message}",
                        fontSize = 11.sp,
                        color = when (entry.level) {
                            LogLevel.ERROR, LogLevel.CRITICAL -> MaterialTheme.colorScheme.error
                            LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
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

private fun formatTime(timestamp: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

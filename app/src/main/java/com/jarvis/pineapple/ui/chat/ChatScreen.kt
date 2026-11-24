package com.jarvis.pineapple.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.pineapple.chat.AssistantCommandParser
import com.jarvis.pineapple.chat.Conversation
import com.jarvis.pineapple.chat.Role
import com.jarvis.pineapple.system.PermissionManager
import com.jarvis.pineapple.ui.permission.rememberOnDemandPermission
import kotlinx.coroutines.launch
import org.koin.androidx.compose.get
import org.koin.androidx.compose.koinViewModel

/**
 * 主聊天界面（参考 Kai UI 设计）
 *
 * 设计特点：
 * - 简洁的卡片式消息气泡
 * - 清晰的用户/AI 区分
 * - 流畅的输入体验
 * - 模型选择快捷入口
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = koinViewModel(),
    onNavigateToVoiceChat: () -> Unit = {},
    onNavigateToModelSelection: () -> Unit = {},
    onNavigateToVm: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val speechManager = get<com.jarvis.pineapple.speech.SpeechManager>()
    val voiceManager = get<com.jarvis.pineapple.speech.VoiceManager>()
    val wakeEngine = get<com.jarvis.pineapple.wake.WakeEngine>()
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val activeModelName by viewModel.activeModelName.collectAsState()
    val schedulingInfo by viewModel.lastSchedulingInfo.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val persistenceError by viewModel.persistenceError.collectAsState()
    val wakeUnavailable by wakeEngine.unavailableReasons.collectAsState()
    val pendingAttachments by viewModel.pendingAttachments.collectAsState()
    val attachmentNotice by viewModel.attachmentNotice.collectAsState()
    var input by remember { mutableStateOf("") }
    var voiceNotice by remember { mutableStateOf<String?>(null) }
    var ioNotice by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val clipboard = LocalClipboardManager.current
    val requestMic = rememberOnDemandPermission(
        permissions = PermissionManager.MICROPHONE_PERMISSIONS,
        onGranted = onNavigateToVoiceChat,
        onDenied = { voiceNotice = it }
    )

    // 导出对话（全部 / 当前）：由系统文件选择器指定保存位置。
    var pendingExportId by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val id = pendingExportId
                val ok = if (id == null) viewModel.exportAllTo(uri) else viewModel.exportConversationTo(uri, id)
                ioNotice = if (ok) "对话已导出" else "对话导出失败"
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = viewModel.importFrom(uri)
                ioNotice = if (ok) "对话已导入" else "对话导入失败：请检查文件格式"
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationHistoryDrawer(
                conversations = conversations,
                currentId = currentConversationId,
                onNew = {
                    viewModel.newConversation()
                    scope.launch { drawerState.close() }
                },
                onSelect = { id ->
                    viewModel.switchConversation(id)
                    scope.launch { drawerState.close() }
                },
                onDelete = { id -> viewModel.deleteConversation(id) },
                onArchive = { id, archived ->
                    if (!viewModel.archiveConversation(id, archived)) {
                        ioNotice = "归档操作失败：请检查存储状态"
                    }
                },
                onExportAll = {
                    pendingExportId = null
                    exportLauncher.launch("jarvis_conversations.json")
                },
                onExportCurrent = { id ->
                    pendingExportId = id
                    exportLauncher.launch("jarvis_conversation_$id.json")
                },
                onImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) }
            )
        }
    ) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ===== 顶部栏（规格书 3.1：左侧虚拟机入口、中间声音播放按钮、右侧设置入口） =====
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                Icon(Icons.Filled.History, contentDescription = "聊天历史")
            }
            // 左：虚拟机入口
            IconButton(onClick = onNavigateToVm) {
                Icon(Icons.Filled.Terminal, contentDescription = "虚拟机")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "JARVIS",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    activeModelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 中：声音播放按钮（朗读/停止最近一条 AI 回复）
            var speaking by remember { mutableStateOf(false) }
            IconButton(onClick = {
                val last = messages.lastOrNull { it.role == Role.ASSISTANT && it.content.isNotBlank() }
                if (last != null) {
                    if (speaking) {
                        speechManager.stopSpeaking()
                        speaking = false
                    } else {
                        voiceManager.resolveSpeakVoice()
                        voiceNotice = voiceManager.consumeFallbackNotice()
                        speechManager.speak(last.content)
                        speaking = true
                    }
                }
            }) {
                Icon(
                    if (speaking) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (speaking) "停止朗读" else "朗读最近回复"
                )
            }
            IconButton(onClick = { requestMic() }) {
                Icon(Icons.Filled.Mic, contentDescription = "语音对话")
            }
            // 右：设置入口
            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置")
            }
        }

        // ===== 中部：消息列表 =====
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(messages, key = { _, msg -> msg.id }) { index, msg ->
                MessageBubble(
                    msg = msg,
                    canRegenerate = index == messages.lastIndex &&
                        msg.role == Role.ASSISTANT && !msg.isStreaming && !isGenerating,
                    onCopy = {
                        clipboard.setText(AnnotatedString(msg.content))
                        ioNotice = "已复制消息"
                    },
                    onQuote = {
                        input = msg.content.lineSequence()
                            .joinToString("\n") { "> $it" } + "\n"
                        ioNotice = "已引用该消息，可继续输入"
                    },
                    onDelete = {
                        viewModel.deleteMessage(msg.id)
                        ioNotice = "已删除该消息"
                    },
                    onRegenerate = { viewModel.regenerateLast() }
                )
            }
        }

        if (voiceNotice != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = voiceNotice!!,
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        ioNotice?.let { notice ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = notice,
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        persistenceError?.let { error ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        if (wakeUnavailable.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = wakeUnavailable.joinToString("\n"),
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // ===== 调度信息提示 =====
        if (schedulingInfo != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = schedulingInfo!!,
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(AssistantCommandParser.CHAT_SHORTCUTS, key = { it.first }) { (command, label) ->
                TextButton(
                    onClick = {
                        if (!isGenerating) {
                            scope.launch {
                                viewModel.sendMessage(AssistantCommandParser.asExplicitCommand(command))
                            }
                        }
                    },
                    enabled = !isGenerating
                ) {
                    Text(label, fontSize = 12.sp)
                }
            }
        }

        // ===== 待发送附件（选择后不立即发送，等用户确认文字再发） =====
        if (pendingAttachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pendingAttachments, key = { it.id }) { attachment ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${attachment.displayName} · ${attachment.summary}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            IconButton(
                                onClick = { viewModel.removeAttachment(attachment.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除附件",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        attachmentNotice?.let { notice ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { viewModel.clearAttachmentNotice() },
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = notice,
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // ===== 底部：输入区 =====
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 模型选择按钮（规格书 3.1：模型选择按钮，默认：本地模型）
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onNavigateToModelSelection) {
                        Icon(Icons.Filled.Settings, contentDescription = "模型选择")
                    }
                    Text(
                        "默认：$activeModelName",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 添加附件：只暂存，不发送；也可直接粘贴手机文件管理器的绝对路径。
                val pickFile = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let { viewModel.addAttachment(it) } }
                var attachMenuExpanded by remember { mutableStateOf(false) }
                var pathDialogVisible by remember { mutableStateOf(false) }
                var pathInput by remember { mutableStateOf("") }
                Box {
                    IconButton(onClick = { attachMenuExpanded = true }) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "添加附件")
                    }
                    DropdownMenu(
                        expanded = attachMenuExpanded,
                        onDismissRequest = { attachMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("选择图片/文件") },
                            onClick = {
                                attachMenuExpanded = false
                                pickFile.launch(arrayOf("*/*"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("输入绝对路径") },
                            onClick = {
                                attachMenuExpanded = false
                                pathDialogVisible = true
                            }
                        )
                    }
                }

                if (pathDialogVisible) {
                    AlertDialog(
                        onDismissRequest = { pathDialogVisible = false },
                        title = { Text("按绝对路径添加附件") },
                        text = {
                            OutlinedTextField(
                                value = pathInput,
                                onValueChange = { pathInput = it },
                                placeholder = {
                                    Text("/storage/emulated/0/DCIM/Camera/IMG_001.jpg")
                                },
                                singleLine = true
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pathDialogVisible = false
                                    viewModel.addAttachment(pathInput)
                                    pathInput = ""
                                }
                            ) { Text("添加") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pathDialogVisible = false }) { Text("取消") }
                        }
                    )
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("与 JARVIS 对话（/ 开头执行本机指令）") },
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp)
                )

                Spacer(Modifier.width(8.dp))

                FilledIconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty() || pendingAttachments.isNotEmpty()) {
                            viewModel.sendPendingMessage(text)
                            input = ""
                        }
                    },
                    enabled = !isGenerating && (input.isNotBlank() || pendingAttachments.isNotEmpty())
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
    }
}

@Composable
private fun ConversationHistoryDrawer(
    conversations: List<Conversation>,
    currentId: String?,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onArchive: (String, Boolean) -> Unit,
    onExportAll: () -> Unit,
    onExportCurrent: (String) -> Unit,
    onImport: () -> Unit
) {
    val active = conversations.filterNot { it.archived }
    val archived = conversations.filter { it.archived }

    ModalDrawerSheet(modifier = Modifier.width(300.dp).fillMaxHeight()) {
        Text(
            "聊天历史",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        TextButton(onClick = onNew, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("新建对话")
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (active.isEmpty()) {
                item {
                    Text(
                        "暂无已保存对话",
                        modifier = Modifier.padding(16.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(active, key = { it.id }) { conv ->
                ConversationRow(
                    conv = conv,
                    isCurrent = conv.id == currentId,
                    actionLabel = "归档",
                    onSelect = { onSelect(conv.id) },
                    onAction = { onArchive(conv.id, true) },
                    onDelete = { onDelete(conv.id) }
                )
            }
            if (archived.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        "已归档（${archived.size}）",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(archived, key = { it.id }) { conv ->
                    ConversationRow(
                        conv = conv,
                        isCurrent = conv.id == currentId,
                        actionLabel = "恢复",
                        onSelect = { onSelect(conv.id) },
                        onAction = { onArchive(conv.id, false) },
                        onDelete = { onDelete(conv.id) }
                    )
                }
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { currentId?.let(onExportCurrent) },
                enabled = currentId != null
            ) { Text("导出当前", fontSize = 12.sp) }
            TextButton(onClick = onExportAll) { Text("导出全部", fontSize = 12.sp) }
            TextButton(onClick = onImport) { Text("导入", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ConversationRow(
    conv: Conversation,
    isCurrent: Boolean,
    actionLabel: String,
    onSelect: () -> Unit,
    onAction: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                conv.title,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                "${conv.messages.size} 条消息",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onAction) { Text(actionLabel, fontSize = 11.sp) }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除对话")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: com.jarvis.pineapple.chat.ChatMessage,
    canRegenerate: Boolean,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit
) {
    val isUser = msg.role == Role.USER
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
        if (!isUser) {
            // AI 头像
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "J",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { menuExpanded = true }
                )
        ) {
            Text(
                text = msg.content.ifEmpty { if (msg.isStreaming) "..." else "" },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp
            )
        }

        if (isUser) {
            Spacer(Modifier.width(8.dp))
            // 用户头像
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "我",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("复制") },
                onClick = {
                    menuExpanded = false
                    onCopy()
                }
            )
            DropdownMenuItem(
                text = { Text("引用") },
                onClick = {
                    menuExpanded = false
                    onQuote()
                }
            )
            if (!msg.isStreaming && msg.content.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("删除") },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
            if (canRegenerate) {
                DropdownMenuItem(
                    text = { Text("重新生成") },
                    onClick = {
                        menuExpanded = false
                        onRegenerate()
                    }
                )
            }
        }
    }
}

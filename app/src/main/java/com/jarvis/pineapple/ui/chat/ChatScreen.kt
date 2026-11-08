package com.jarvis.pineapple.ui.chat

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val wakeUnavailable by wakeEngine.unavailableReasons.collectAsState()
    var input by remember { mutableStateOf("") }
    var voiceNotice by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val requestMic = rememberOnDemandPermission(
        permissions = PermissionManager.MICROPHONE_PERMISSIONS,
        onGranted = onNavigateToVoiceChat,
        onDenied = { voiceNotice = it }
    )

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
                onDelete = { id -> viewModel.deleteConversation(id) }
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
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
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

                // 上传文件/图片（真实 SAF 文件选择器）
                val pickFile = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let { viewModel.sendAttachment(it) } }
                IconButton(onClick = { pickFile.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "上传文件/图片")
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("与 JARVIS 对话...") },
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp)
                )

                Spacer(Modifier.width(8.dp))

                FilledIconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            scope.launch {
                                viewModel.sendMessage(text)
                                input = ""
                            }
                        }
                    },
                    enabled = !isGenerating && input.isNotBlank()
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
    onDelete: (String) -> Unit
) {
    ModalDrawerSheet(modifier = Modifier.width(280.dp).fillMaxHeight()) {
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
        if (conversations.isEmpty()) {
            Text(
                "暂无已保存对话",
                modifier = Modifier.padding(16.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn {
                items(conversations, key = { it.id }) { conv ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(conv.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                conv.title,
                                fontWeight = if (conv.id == currentId) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                "${conv.messages.size} 条消息",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onDelete(conv.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除对话")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: com.jarvis.pineapple.chat.ChatMessage) {
    val isUser = msg.role == Role.USER

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
            modifier = Modifier.widthIn(max = 300.dp)
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
}

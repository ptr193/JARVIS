package com.jarvis.pineapple.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.pineapple.chat.ChatEngine
import com.jarvis.pineapple.chat.Role
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * 主聊天界面
 *
 * 顶部：左侧"虚拟机"入口、中间声音播放按钮、右侧"设置"入口
 * 中部：对话消息列表
 * 底部：模型选择按钮、上传、输入框、发送按钮
 */
@Composable
fun ChatScreen(
    onOpenVm: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlayVoice: () -> Unit,
    viewModel: ChatViewModel = koinViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val activeModelName by viewModel.activeModelName.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ===== 顶部栏 =====
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenVm) {
                Icon(Icons.Filled.Dns, contentDescription = "虚拟机", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onPlayVoice) {
                Icon(Icons.Filled.VolumeUp, contentDescription = "声音", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.primary)
            }
        }

        // ===== 中部：消息列表 =====
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
        }

        // ===== 底部：输入区 =====
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            // 模型选择 + 上传
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { /* 打开模型选择 */ },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.width(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(activeModelName, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { /* 上传文件/图片 */ }) {
                    Text("📎", fontSize = 22.sp)
                }
            }
            // 输入框 + 发送
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("与 JARVIS 对话...") },
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            scope.launch {
                                viewModel.sendMessage(text)
                                input = ""
                            }
                        }
                    },
                    enabled = !isGenerating
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "发送", tint = MaterialTheme.colorScheme.primary)
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
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                Text(
                    text = if (isUser) "我" else "JARVIS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isUser) Color.White else MaterialTheme.colorScheme.primary
                )
                Text(
                    text = msg.content.ifEmpty { if (msg.isStreaming) "..." else "" },
                    color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp
                )
            }
        }
    }
}

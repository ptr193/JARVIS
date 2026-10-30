package com.jarvis.pineapple.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 语音对话界面。
 *
 * 功能：
 * - 语音输入
 * - 语音合成输出
 * - 实时状态显示
 */
@Composable
fun VoiceChatScreen(
    onBack: () -> Unit,
    speechManager: com.jarvis.pineapple.speech.SpeechManager,
    voiceWakeManager: com.jarvis.pineapple.speech.VoiceWakeManager
) {
    val ttsState by speechManager.ttsState.collectAsState()
    val wakeState by voiceWakeManager.wakeState.collectAsState()
    val conversationState by speechManager.conversationState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 状态显示
        when {
            conversationState.isListening -> {
                Text(
                    "正在聆听...",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            conversationState.isProcessing -> {
                Text(
                    "正在处理...",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            conversationState.isSpeaking -> {
                Text(
                    "正在回复...",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            wakeState.isWoken -> {
                Text(
                    "JARVIS 已唤醒",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            else -> {
                Text(
                    "点击开始语音对话",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 语音输入按钮
        Button(
            onClick = {
                if (conversationState.isListening || conversationState.isProcessing || conversationState.isSpeaking) {
                    speechManager.stopVoiceConversation()
                    voiceWakeManager.stopListening()
                } else {
                    speechManager.startVoiceConversation()
                    voiceWakeManager.startListening()
                }
            },
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
        ) {
            if (conversationState.isListening || conversationState.isProcessing || conversationState.isSpeaking) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = "停止",
                    modifier = Modifier.size(48.dp)
                )
            } else {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = "开始",
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 当前输入/输出显示
        if (conversationState.currentInput.isNotEmpty()) {
            Text(
                "输入: ${conversationState.currentInput}",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp)
            )
        }

        if (conversationState.currentOutput.isNotEmpty()) {
            Text(
                "输出: ${conversationState.currentOutput}",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 灵敏度设置
        Text(
            "唤醒灵敏度",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                com.jarvis.pineapple.speech.VoiceWakeManager.WakeSensitivity.LOW to "低",
                com.jarvis.pineapple.speech.VoiceWakeManager.WakeSensitivity.MEDIUM to "中",
                com.jarvis.pineapple.speech.VoiceWakeManager.WakeSensitivity.HIGH to "高"
            ).forEach { (sensitivity, label) ->
                val isSelected = wakeState.sensitivity == sensitivity
                Button(
                    onClick = {
                        // 更新灵敏度
                    },
                    colors = if (isSelected) {
                        androidx.compose.material3.ButtonDefaults.buttonColors()
                    } else {
                        androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                    }
                ) {
                    Text(label)
                }
            }
        }
    }
}

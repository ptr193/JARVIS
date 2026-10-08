package com.jarvis.pineapple.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.pineapple.chat.ChatEngine
import com.jarvis.pineapple.model.ModelScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val chatEngine: ChatEngine,
    private val modelScheduler: ModelScheduler
) : ViewModel() {

    val messages = chatEngine.messages

    val isGenerating = chatEngine.isGenerating

    val activeModelName: StateFlow<String> = modelScheduler.activeModel
        .map { it.info.name }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "本地模型")

    fun sendMessage(content: String) {
        viewModelScope.launch {
            chatEngine.sendUserMessage(content).collect { /* 流式更新已在 engine 内处理 */ }
        }
    }
}

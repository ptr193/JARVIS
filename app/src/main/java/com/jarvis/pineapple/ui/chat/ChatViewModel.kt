package com.jarvis.pineapple.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.pineapple.chat.ChatEngine
import com.jarvis.pineapple.chat.Conversation
import com.jarvis.pineapple.model.ModelScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class ChatViewModel(
    private val chatEngine: ChatEngine,
    private val modelScheduler: ModelScheduler,
    private val context: Context
) : ViewModel() {

    val messages = chatEngine.messages

    val isGenerating = chatEngine.isGenerating

    val lastSchedulingInfo: StateFlow<String?> = chatEngine.lastSchedulingInfo

    val conversations: StateFlow<List<Conversation>> = chatEngine.conversations

    val currentConversationId: StateFlow<String?> = chatEngine.currentConversationId

    val activeModelName: StateFlow<String> = modelScheduler.activeModel
        .map { it.info.name }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "本地模型")

    fun sendMessage(content: String) {
        viewModelScope.launch {
            chatEngine.sendUserMessage(content).collect { /* 流式更新已在 engine 内处理 */ }
        }
    }

    /**
     * 上传文件/图片（规格书 3.1 主界面底部 [📎上传]）。
     *
     * 真实读取用户选择的文件：文本类提取内容摘要，图片类提取尺寸与 SHA-256，
     * 组装为附件说明消息发送给对话引擎，由模型基于附件内容应答。
     */
    fun sendAttachment(uri: Uri) {
        viewModelScope.launch {
            val desc = withContext(Dispatchers.IO) { describeAttachment(uri) }
            if (desc != null) {
                chatEngine.sendUserMessage(desc).collect { }
            }
        }
    }

    private fun describeAttachment(uri: Uri): String? = try {
        val resolver = context.contentResolver
        val cursor = resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                Pair(
                    if (nameIdx >= 0) c.getString(nameIdx) else "未命名文件",
                    if (sizeIdx >= 0) c.getLong(sizeIdx) else -1L
                )
            } else null
        } ?: return null
        val (name, size) = cursor
        val type = resolver.getType(uri) ?: "application/octet-stream"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return "【附件】$name（读取失败，请重试）"
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        when {
            type.startsWith("image/") -> {
                val opts = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                "【图片附件】$name（${opts.outWidth}x${opts.outHeight}，${bytes.size} 字节，SHA-256: $sha256）。" +
                    "请基于该图片信息回答；如需查看图片内容请告知我具体需求。"
            }
            type.startsWith("text/") || name.endsWith(".md") || name.endsWith(".json") ||
                name.endsWith(".csv") || name.endsWith(".log") || name.endsWith(".kt") ||
                name.endsWith(".java") || name.endsWith(".py") || name.endsWith(".sh") -> {
                val text = bytes.toString(Charsets.UTF_8)
                val excerpt = if (text.length > MAX_TEXT_CHARS) {
                    text.take(MAX_TEXT_CHARS) + "\n…（共 ${text.length} 字符，已截断）"
                } else text
                "【文件附件】$name（$type，${bytes.size} 字节，SHA-256: $sha256）内容如下：\n$excerpt"
            }
            else -> "【文件附件】$name（$type，${bytes.size} 字节，SHA-256: $sha256）。" +
                "该类型不支持直接预览内容。"
        }
    } catch (e: Exception) {
        "【附件】读取失败：${e.message}"
    }

    fun newConversation() {
        chatEngine.newConversation()
    }

    fun switchConversation(conversationId: String) {
        chatEngine.switchConversation(conversationId)
    }

    fun deleteConversation(conversationId: String) {
        chatEngine.deleteConversation(conversationId)
    }

    fun clearConversation() {
        chatEngine.clearConversation()
    }

    companion object {
        private const val MAX_TEXT_CHARS = 24_000
    }
}

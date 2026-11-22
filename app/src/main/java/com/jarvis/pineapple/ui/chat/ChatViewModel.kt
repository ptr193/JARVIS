package com.jarvis.pineapple.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.pineapple.chat.ChatEngine
import com.jarvis.pineapple.chat.Conversation
import com.jarvis.pineapple.model.ImageInput
import com.jarvis.pineapple.model.ModelScheduler
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val persistenceError: StateFlow<String?> = chatEngine.persistenceError

    val activeModelName: StateFlow<String> = modelScheduler.activeModel
        .map { it.info.name }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "本地模型")

    fun sendMessage(content: String) {
        viewModelScope.launch {
            chatEngine.sendUserMessage(content).collect { /* 流式更新已在 engine 内处理 */ }
        }
    }

    fun deleteMessage(messageId: String) {
        chatEngine.deleteMessage(messageId)
    }

    /** 删除最后一条助手回复并重新生成；用户输入不会重复写入。 */
    fun regenerateLast() {
        viewModelScope.launch {
            val user = chatEngine.prepareRegeneration() ?: return@launch
            chatEngine.sendUserMessage(user, appendUserMessage = false).collect { }
        }
    }

    /**
     * 上传文件/图片（规格书 3.1 主界面底部 [📎上传]）。
     *
     * - 图片：读取真实字节并以 base64 交给支持视觉的模型；不支持的模型会得到明确解释。
     * - 文本：流式读取并附带真实内容摘要。
     * - 其他二进制：只计算摘要，不谎称模型可读取内容。
     */
    fun sendAttachment(uri: Uri) {
        viewModelScope.launch {
            when (val payload = withContext(Dispatchers.IO) { readAttachment(uri) }) {
                is AttachmentPayload.Text -> chatEngine.sendUserMessage(payload.description).collect { }
                is AttachmentPayload.Image -> chatEngine.sendUserMessage(
                    payload.description,
                    image = payload.image
                ).collect { }
                null -> Unit
            }
        }
    }

    private sealed interface AttachmentPayload {
        data class Text(val description: String) : AttachmentPayload
        data class Image(val description: String, val image: ImageInput) : AttachmentPayload
    }

    private fun readAttachment(uri: Uri): AttachmentPayload? {
        val resolver = context.contentResolver
        val meta = resolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            Pair(
                if (nameIdx >= 0) c.getString(nameIdx) else "未命名文件",
                if (sizeIdx >= 0) c.getLong(sizeIdx) else -1L
            )
        } ?: return null
        val (name, declaredSize) = meta
        val type = resolver.getType(uri) ?: "application/octet-stream"

        if (type.startsWith("image/")) {
            val bytes = resolver.openInputStream(uri)
                ?.use { readBounded(it, MAX_IMAGE_BYTES + 1) }
                ?: return AttachmentPayload.Text("【附件】$name（读取失败，请重试）")
            if (bytes.size > MAX_IMAGE_BYTES) {
                return AttachmentPayload.Text(
                    "【图片附件】$name 超过 ${MAX_IMAGE_BYTES / 1024 / 1024}MB 上限，未上传。请压缩后重试。"
                )
            }
            if (type !in SUPPORTED_IMAGE_TYPES) {
                return AttachmentPayload.Text(
                    "【图片附件】$name（$type）暂不支持模型识别，请转换为 JPEG/PNG/WebP 后重试。"
                )
            }
            val sha = digest(bytes)
            val opts = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val description = "【图片附件】$name（${opts.outWidth}x${opts.outHeight}，" +
                "${bytes.size} 字节，SHA-256: $sha）。请识别并回答图片内容。"
            return AttachmentPayload.Image(
                description,
                ImageInput(
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    mimeType = type
                )
            )
        }

        val isText = isTextFile(name, type)
        val digest = MessageDigest.getInstance("SHA-256")
        val textBuffer = ByteArrayOutputStream()
        var total = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
                total += n
                if (isText && textBuffer.size() < MAX_TEXT_BYTES) {
                    textBuffer.write(buffer, 0, minOf(n, MAX_TEXT_BYTES - textBuffer.size()))
                }
            }
        } ?: return AttachmentPayload.Text("【附件】$name（读取失败，请重试）")

        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val size = if (declaredSize > 0) declaredSize else total
        return if (isText) {
            val text = textBuffer.toByteArray().toString(Charsets.UTF_8)
            val excerpt = if (text.length > MAX_TEXT_CHARS) {
                text.take(MAX_TEXT_CHARS) + "\n…（共 ${text.length} 字符，已截断）"
            } else {
                text
            }
            AttachmentPayload.Text(
                "【文件附件】$name（$type，$size 字节，SHA-256: $sha）内容如下：\n$excerpt"
            )
        } else {
            AttachmentPayload.Text(
                "【文件附件】$name（$type，$size 字节，SHA-256: $sha）。该类型不支持直接预览内容。"
            )
        }
    }

    private fun isTextFile(name: String, type: String): Boolean =
        type.startsWith("text/") || name.endsWith(".md", true) || name.endsWith(".json", true) ||
            name.endsWith(".csv", true) || name.endsWith(".log", true) || name.endsWith(".kt", true) ||
            name.endsWith(".java", true) || name.endsWith(".py", true) || name.endsWith(".sh", true) ||
            name.endsWith(".xml", true) || name.endsWith(".yaml", true) || name.endsWith(".yml", true)

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun newConversation(): Boolean = chatEngine.newConversation()

    fun switchConversation(conversationId: String): Boolean = chatEngine.switchConversation(conversationId)

    fun deleteConversation(conversationId: String): Boolean = chatEngine.deleteConversation(conversationId)

    fun archiveConversation(conversationId: String, archived: Boolean): Boolean =
        chatEngine.archiveConversation(conversationId, archived)

    /** 将全部对话导出到用户选择的文件（SAF Uri）；返回是否真实写入成功。 */
    suspend fun exportAllTo(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val json = chatEngine.exportAllConversations()
            val stream = context.contentResolver.openOutputStream(uri) ?: return@runCatching false
            stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            true
        }.getOrDefault(false)
    }

    /** 将单个对话导出到用户选择的文件（SAF Uri）。 */
    suspend fun exportConversationTo(uri: Uri, conversationId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val json = chatEngine.exportConversation(conversationId) ?: return@runCatching false
            val stream = context.contentResolver.openOutputStream(uri) ?: return@runCatching false
            stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            true
        }.getOrDefault(false)
    }

    /** 从用户选择的文件导入对话；返回是否导入成功。 */
    suspend fun importFrom(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@runCatching false
            chatEngine.importConversations(text)
        }.getOrDefault(false)
    }

    fun clearConversation(): Boolean = chatEngine.clearConversation()

    companion object {
        private const val MAX_TEXT_CHARS = 24_000
        private const val MAX_TEXT_BYTES = 1_000_000
        private const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
        private val SUPPORTED_IMAGE_TYPES =
            setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}

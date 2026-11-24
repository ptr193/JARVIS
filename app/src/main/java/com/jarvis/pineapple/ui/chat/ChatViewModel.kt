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
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** 已选择但尚未发送的附件。发送前只展示，不调用模型。 */
    data class PendingAttachment(
        val id: String,
        val displayName: String,
        val summary: String,
        val textBlock: String,
        val image: ImageInput? = null
    )

    private val _pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<PendingAttachment>> = _pendingAttachments.asStateFlow()

    private val _attachmentNotice = MutableStateFlow<String?>(null)
    val attachmentNotice: StateFlow<String?> = _attachmentNotice.asStateFlow()

    fun clearAttachmentNotice() {
        _attachmentNotice.value = null
    }

    /**
     * 选择一个附件但**不发送**：先放入输入框上方的待发送区，等用户确认文字后按发送。
     *
     * - 图片：读取真实字节并以 base64 交给支持视觉的模型；不支持的模型会得到明确解释。
     * - 文本：流式读取并附带真实内容摘要。
     * - 其他二进制：只计算摘要，不谎称模型可读取内容。
     */
    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            val payload = withContext(Dispatchers.IO) {
                runCatching { readAttachment(uri) }.getOrNull()
            }
            if (payload == null) {
                _attachmentNotice.value = "附件读取失败，请重试或换一个文件"
            } else {
                stage(payload)
            }
        }
    }

    /**
     * 按绝对路径添加附件（如 `/storage/emulated/0/DCIM/Camera/IMG_001.jpg`）。
     * 用户可以先让 AI 在文件管理器中找到文件，再把路径贴进来。
     */
    fun addAttachment(path: String) {
        val trimmed = path.trim()
        if (!trimmed.startsWith("/")) {
            _attachmentNotice.value = "请输入以 / 开头的绝对路径"
            return
        }
        val file = File(trimmed)
        if (!file.isFile) {
            _attachmentNotice.value = "文件不存在：$trimmed"
            return
        }
        if (!file.canRead()) {
            _attachmentNotice.value = "无权读取该路径。请在系统设置中授予“所有文件访问权限”，或改用文件选择器。"
            return
        }
        viewModelScope.launch {
            val payload = withContext(Dispatchers.IO) {
                runCatching { readAttachment(file) }.getOrNull()
            }
            if (payload == null) {
                _attachmentNotice.value = "附件读取失败：$trimmed"
            } else {
                stage(payload)
            }
        }
    }

    fun removeAttachment(id: String) {
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id == id }
    }

    fun clearAttachments() {
        _pendingAttachments.value = emptyList()
    }

    private fun stage(payload: PendingAttachment) {
        val current = _pendingAttachments.value
        if (payload.image != null && current.any { it.image != null }) {
            _attachmentNotice.value = "每条消息最多附加 1 张图片，请分多条发送或在下方移除当前图片"
            return
        }
        _pendingAttachments.value = current + payload
    }

    /**
     * 发送输入框文字 + 待发送附件。附件按顺序拼进消息正文；
     * 图片作为视觉输入单独传给支持视觉的模型。
     */
    fun sendPendingMessage(text: String) {
        val pending = _pendingAttachments.value
        if (pending.isEmpty()) {
            sendMessage(text)
            return
        }
        val content = composeMessageText(text, pending.map { it.textBlock })
        if (content.isBlank()) {
            _attachmentNotice.value = "请输入要发送的内容，或移除附件"
            return
        }
        val image = pending.firstOrNull { it.image != null }?.image
        _pendingAttachments.value = emptyList()
        viewModelScope.launch {
            chatEngine.sendUserMessage(content, image = image).collect { }
        }
    }

    private fun readAttachment(uri: Uri): PendingAttachment? {
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
        return readPayload(name, type, declaredSize) { resolver.openInputStream(uri) }
    }

    private fun readAttachment(file: File): PendingAttachment {
        val name = file.name
        val type = guessMimeType(name)
        return readPayload(name, type, file.length()) { FileInputStream(file) }
            ?: PendingAttachment(
                id = UUID.randomUUID().toString(),
                displayName = name,
                summary = "读取失败",
                textBlock = "【附件】$name（读取失败，请重试）"
            )
    }

    private fun readPayload(
        name: String,
        type: String,
        declaredSize: Long,
        open: () -> InputStream?
    ): PendingAttachment? {
        val base = { summary: String, block: String, image: ImageInput? ->
            PendingAttachment(
                id = UUID.randomUUID().toString(),
                displayName = name,
                summary = summary,
                textBlock = block,
                image = image
            )
        }
        if (type.startsWith("image/")) {
            val bytes = open()?.use { readBounded(it, MAX_IMAGE_BYTES + 1) }
                ?: return base("读取失败", "【附件】$name（读取失败，请重试）", null)
            if (bytes.size > MAX_IMAGE_BYTES) {
                return base(
                    "超过 ${MAX_IMAGE_BYTES / 1024 / 1024}MB",
                    "【图片附件】$name 超过 ${MAX_IMAGE_BYTES / 1024 / 1024}MB 上限，未上传。请压缩后重试。",
                    null
                )
            }
            if (type !in SUPPORTED_IMAGE_TYPES) {
                return base(
                    "不支持的图片格式",
                    "【图片附件】$name（$type）暂不支持模型识别，请转换为 JPEG/PNG/WebP 后重试。",
                    null
                )
            }
            val sha = digest(bytes)
            val opts = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val description = "【图片附件】$name（${opts.outWidth}x${opts.outHeight}，" +
                "${bytes.size} 字节，SHA-256: $sha）。请识别并回答图片内容。"
            return base(
                "图片 ${opts.outWidth}x${opts.outHeight} · ${formatSize(bytes.size.toLong())}",
                description,
                ImageInput(base64 = Base64.encodeToString(bytes, Base64.NO_WRAP), mimeType = type)
            )
        }

        val isText = isTextFile(name, type)
        val digest = MessageDigest.getInstance("SHA-256")
        val textBuffer = ByteArrayOutputStream()
        var total = 0L
        val stream = open() ?: return base("读取失败", "【附件】$name（读取失败，请重试）", null)
        stream.use { input ->
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
        }

        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val size = if (declaredSize > 0) declaredSize else total
        return if (isText) {
            val text = textBuffer.toByteArray().toString(Charsets.UTF_8)
            val excerpt = if (text.length > MAX_TEXT_CHARS) {
                text.take(MAX_TEXT_CHARS) + "\n…（共 ${text.length} 字符，已截断）"
            } else {
                text
            }
            base(
                "文本 · ${formatSize(size)}",
                "【文件附件】$name（$type，$size 字节，SHA-256: $sha）内容如下：\n$excerpt",
                null
            )
        } else {
            base(
                "文件 · ${formatSize(size)}",
                "【文件附件】$name（$type，$size 字节，SHA-256: $sha）。该类型不支持直接预览内容。",
                null
            )
        }
    }

    private fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heic"
        "txt", "log", "md", "csv" -> "text/plain"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
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
        /**
         * 把用户输入与附件块合并为一条消息：文字在前，附件按选择顺序追加。
         * 只有附件时直接发送附件内容，避免产生空消息。
         */
        internal fun composeMessageText(userText: String, attachmentBlocks: List<String>): String {
            val blocks = attachmentBlocks.map { it.trim() }.filter { it.isNotEmpty() }
            val text = userText.trim()
            return when {
                blocks.isEmpty() -> text
                text.isEmpty() -> blocks.joinToString("\n\n")
                else -> text + "\n\n" + blocks.joinToString("\n\n")
            }
        }

        private const val MAX_TEXT_CHARS = 24_000
        private const val MAX_TEXT_BYTES = 1_000_000
        private const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
        private val SUPPORTED_IMAGE_TYPES =
            setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}

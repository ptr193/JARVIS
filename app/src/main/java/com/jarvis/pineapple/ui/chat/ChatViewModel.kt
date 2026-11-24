package com.jarvis.pineapple.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.pineapple.chat.ChatEngine
import com.jarvis.pineapple.chat.Conversation
import com.jarvis.pineapple.model.ModelScheduler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
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
        val absolutePath: String,
        val appOwnedCopy: File? = null
    )

    private val _pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<PendingAttachment>> = _pendingAttachments.asStateFlow()

    private val _attachmentNotice = MutableStateFlow<String?>(null)
    val attachmentNotice: StateFlow<String?> = _attachmentNotice.asStateFlow()

    fun clearAttachmentNotice() {
        _attachmentNotice.value = null
    }

    /** 选择附件只暂存；多选结果按顺序处理，超出容量的部分不会复制。 */
    fun addAttachment(uri: Uri) = addAttachments(listOf(uri))

    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val acceptedCount = acceptedAttachmentCount(_pendingAttachments.value.size, uris.size)
            val accepted = uris.take(acceptedCount)
            val notices = mutableListOf<String>()
            if (acceptedCount < uris.size) {
                notices += if (acceptedCount == 0) {
                    "最多可暂存 $MAX_ATTACHMENTS 个附件，当前容量已满；本次 ${uris.size} 个均未添加"
                } else {
                    "最多可暂存 $MAX_ATTACHMENTS 个附件；本次仅接收前 $acceptedCount 个，" +
                        "其余 ${uris.size - acceptedCount} 个未添加"
                }
            }

            accepted.forEachIndexed { index, uri ->
                val result = withContext(Dispatchers.IO) {
                    runCatching { readAttachment(uri) }
                }
                val payload = result.getOrNull()
                if (payload == null) {
                    val error = result.exceptionOrNull()
                    val detail = error?.message ?: error?.javaClass?.simpleName ?: "未知错误"
                    notices += "第 ${index + 1} 个附件添加失败：$detail"
                } else if (!stage(payload)) {
                    notices += "附件数量已达 $MAX_ATTACHMENTS 个，${payload.displayName} 未添加"
                    val failures = withContext(Dispatchers.IO) {
                        deletePrivateCopies(listOf(payload))
                    }
                    if (failures.isNotEmpty()) {
                        notices += "未接收附件的私有副本清理失败：${failures.joinToString("、")}"
                    }
                }
            }
            if (notices.isNotEmpty()) appendAttachmentNotice(notices.joinToString("\n"))
        }
    }

    /**
     * 按绝对路径添加附件（如 `/storage/emulated/0/DCIM/Camera/IMG_001.jpg`）。
     * 手输路径只读取、不复制，也绝不会在移除或清空时删除源文件。
     */
    fun addAttachment(path: String) {
        val trimmed = path.trim()
        if (!trimmed.startsWith("/")) {
            _attachmentNotice.value = "请输入以 / 开头的绝对路径"
            return
        }
        if (acceptedAttachmentCount(_pendingAttachments.value.size, 1) == 0) {
            _attachmentNotice.value = "最多可暂存 $MAX_ATTACHMENTS 个附件，请先移除部分附件"
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
            val result = withContext(Dispatchers.IO) {
                runCatching { readAttachment(file) }
            }
            val payload = result.getOrNull()
            if (payload == null) {
                val error = result.exceptionOrNull()
                val detail = error?.message ?: error?.javaClass?.simpleName ?: "未知错误"
                _attachmentNotice.value = "附件读取失败：$trimmed（$detail）"
            } else if (!stage(payload)) {
                _attachmentNotice.value = "最多可暂存 $MAX_ATTACHMENTS 个附件，请先移除部分附件"
            }
        }
    }

    fun removeAttachment(id: String) {
        val removed = _pendingAttachments.value.filter { it.id == id }
        if (removed.isEmpty()) return
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id == id }
        viewModelScope.launch {
            cleanupPrivateAttachments(removed, "附件已移除")
        }
    }

    fun clearAttachments() {
        val removed = _pendingAttachments.value
        if (removed.isEmpty()) return
        _pendingAttachments.value = emptyList()
        viewModelScope.launch {
            cleanupPrivateAttachments(removed, "待发送附件已清空")
        }
    }

    private fun stage(payload: PendingAttachment): Boolean {
        val current = _pendingAttachments.value
        if (current.size >= MAX_ATTACHMENTS) return false
        _pendingAttachments.value = current + payload
        return true
    }

    /** 发送输入框文字 + 待发送附件；所有附件只以文本形式按选择顺序追加。 */
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
        val sentIds = pending.mapTo(mutableSetOf()) { it.id }
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id in sentIds }
        viewModelScope.launch {
            try {
                chatEngine.sendUserMessage(content).collect { }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                val detail = error.message ?: error.javaClass.simpleName
                appendAttachmentNotice("消息发送失败；附件副本仍保留，可沿原绝对路径重试：$detail")
            }
            // 已发送消息只保存绝对路径，因此私有副本必须继续存在，供后续手机文件操作使用。
        }
    }

    /** SAF 文件先复制到应用私有目录，再从可持久读取的绝对路径生成摘要。 */
    private fun readAttachment(uri: Uri): PendingAttachment {
        val resolver = context.contentResolver
        val queriedName = resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        }
        val name = queriedName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "未命名文件"
        val type = resolver.getType(uri) ?: guessMimeType(name)
        val directory = File(context.filesDir, "attachments")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw java.io.IOException("附件复制失败：无法创建应用私有目录")
        }

        val target = File(directory, UUID.randomUUID().toString() + safeAttachmentExtension(name))
        var copyFinished = false
        try {
            val input = resolver.openInputStream(uri)
                ?: throw java.io.IOException("无法打开源文件")
            input.use { source ->
                target.outputStream().use { output -> source.copyTo(output) }
            }
            copyFinished = true
            return readAttachment(target, name, type, target)
        } catch (error: Exception) {
            val cleanupFailed = runCatching { target.exists() && !target.delete() }
                .getOrDefault(true)
            val action = if (copyFinished) "附件副本读取失败" else "附件复制失败"
            val cleanup = if (cleanupFailed) "；临时副本清理失败" else ""
            val detail = error.message ?: error.javaClass.simpleName
            throw java.io.IOException("$action$cleanup：$detail", error)
        }
    }

    private fun readAttachment(
        file: File,
        displayName: String = file.name,
        mimeType: String = guessMimeType(file.name),
        appOwnedCopy: File? = null
    ): PendingAttachment {
        val base = { summary: String, block: String ->
            PendingAttachment(
                id = UUID.randomUUID().toString(),
                displayName = displayName,
                summary = summary,
                textBlock = block,
                absolutePath = file.absolutePath,
                appOwnedCopy = appOwnedCopy
            )
        }
        val isImage = mimeType.startsWith("image/")
        val isText = isTextFile(displayName, mimeType)
        val digest = MessageDigest.getInstance("SHA-256")
        val textBuffer = ByteArrayOutputStream()
        var total = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
                total += count
                if (isText && textBuffer.size() < MAX_TEXT_BYTES) {
                    textBuffer.write(buffer, 0, minOf(count, MAX_TEXT_BYTES - textBuffer.size()))
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }

        if (isImage) {
            return base(
                "图片 · ${formatSize(total)} · 未上传",
                buildAttachmentText(
                    displayName = displayName,
                    mimeType = mimeType,
                    size = total,
                    sha256 = sha,
                    absolutePath = file.absolutePath,
                    isImage = true
                )
            )
        }
        if (isText) {
            val text = textBuffer.toByteArray().toString(Charsets.UTF_8)
            val excerpt = if (text.length > MAX_TEXT_CHARS) {
                text.take(MAX_TEXT_CHARS) + "\n…（共 ${text.length} 字符，已截断）"
            } else {
                text
            }
            return base(
                "文本 · ${formatSize(total)}",
                buildAttachmentText(
                    displayName = displayName,
                    mimeType = mimeType,
                    size = total,
                    sha256 = sha,
                    absolutePath = file.absolutePath,
                    textExcerpt = excerpt
                )
            )
        }
        return base(
            "文件 · ${formatSize(total)}",
            buildAttachmentText(
                displayName = displayName,
                mimeType = mimeType,
                size = total,
                sha256 = sha,
                absolutePath = file.absolutePath
            )
        )
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

    private suspend fun cleanupPrivateAttachments(attachments: List<PendingAttachment>, action: String) {
        val failures = withContext(Dispatchers.IO) { deletePrivateCopies(attachments) }
        if (failures.isNotEmpty()) {
            appendAttachmentNotice("$action，但应用私有副本清理失败：${failures.joinToString("、")}")
        }
    }

    /** 仅删除 SAF 复制产生的副本；手输路径的 appOwnedCopy 始终为 null。 */
    private fun deletePrivateCopies(attachments: List<PendingAttachment>): List<String> =
        attachments.mapNotNull { it.appOwnedCopy }
            .distinctBy { it.absolutePath }
            .mapNotNull { copy ->
                val deleted = runCatching { !copy.exists() || copy.delete() }.getOrDefault(false)
                if (deleted) null else copy.absolutePath
            }

    private fun appendAttachmentNotice(message: String) {
        val current = _attachmentNotice.value
        _attachmentNotice.value = if (current.isNullOrBlank()) message else "$current\n$message"
    }

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

        internal fun acceptedAttachmentCount(currentCount: Int, requestedCount: Int): Int {
            val remaining = (MAX_ATTACHMENTS - currentCount.coerceAtLeast(0)).coerceAtLeast(0)
            return minOf(remaining, requestedCount.coerceAtLeast(0))
        }

        internal fun safeAttachmentExtension(displayName: String): String {
            val extension = displayName.substringAfterLast('.', "")
            return if (extension.matches(Regex("[A-Za-z0-9]{1,10}"))) {
                ".${extension.lowercase()}"
            } else {
                ""
            }
        }

        internal fun buildAttachmentText(
            displayName: String,
            mimeType: String,
            size: Long,
            sha256: String,
            absolutePath: String,
            isImage: Boolean = false,
            textExcerpt: String? = null
        ): String {
            val label = if (isImage) "【图片附件】" else "【文件附件】"
            val base = "$label$displayName（$mimeType，$size 字节，SHA-256: $sha256）" +
                "\n绝对路径：$absolutePath"
            return when {
                isImage -> "$base\n图片未上传；可沿绝对路径用手机文件能力处理。"
                textExcerpt != null -> "$base\n内容如下：\n$textExcerpt"
                else -> "$base\n该类型不支持直接预览内容。"
            }
        }

        private const val MAX_ATTACHMENTS = 20
        private const val MAX_TEXT_CHARS = 24_000
        private const val MAX_TEXT_BYTES = 1_000_000
    }
}

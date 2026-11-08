package com.jarvis.pineapple.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import com.jarvis.pineapple.net.DownloadMirrors
import java.io.File
import java.security.MessageDigest

/**
 * 模型下载状态
 */
data class ModelDownloadState(
    val modelId: String,
    val modelName: String,
    val status: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val errorMessage: String = ""
)

enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    VERIFYING
}

/**
 * 模型下载管理器。
 *
 * 功能：
 * - 首次启动时自动检测并下载四个默认模型
 * - 支持断点续传
 * - 下载后校验 SHA-256
 * - 下载到应用私有目录 context.filesDir/models/
 *
 * 四个默认模型下载地址（硬编码在代码中）：
 * 1. Gemma 4 E2B 2.5B: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm
 * 2. Gemma 4 12B: https://huggingface.co/litert-community/gemma-4-12B-it-litert-lm/resolve/main/gemma-4-12B-it.litertlm
 * 3. LFM2.5 1.2B Instruct: https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/resolve/main/LFM2.5-1.2B-Instruct_int8.litertlm
 * 4. Qwen 3 0.6B: https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm
 */
class ModelDownloadManager(private val context: Context) {

    private val modelsDir = File(context.filesDir, "models")

    private val httpClient = HttpClient(Android) {
        engine {
            connectTimeout = 15_000
            socketTimeout = 30_000
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = _downloadStates.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    // 模型配置信息
    data class ModelConfig(
        val id: String,
        val name: String,
        val fileName: String,
        val downloadUrl: String,
        val sha256: String = "",
        val sizeBytes: Long = 0,
        val mirrorUrls: List<String> = emptyList()
    )

    companion object {
        /**
         * 四个默认模型配置（下载地址硬编码）
         * 注意：sha256 字段需填入真实下载文件的 SHA-256 值，用于下载后校验。
         * 可通过 `sha256sum <file>` 或在线工具获取真实值。
         */
        val DEFAULT_MODELS = listOf(
            ModelConfig(
                id = "gemma4_e2b_2_5b",
                name = "Gemma 4 E2B 2.5B",
                fileName = "gemma4_e2b_2_5b.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                sha256 = "", // 留空则跳过校验；填入官方 SHA-256 后下载完毕会自动校验
                sizeBytes = 2_684_354_560  // ~2.5GB
            ),
            ModelConfig(
                id = "gemma4_12b",
                name = "Gemma 4 12B",
                fileName = "gemma4_12b.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-12B-it-litert-lm/resolve/main/gemma-4-12B-it.litertlm",
                sha256 = "", // 留空则跳过校验；填入官方 SHA-256 后下载完毕会自动校验
                sizeBytes = 12_884_901_888  // ~12GB
            ),
            ModelConfig(
                id = "lfm2_5_1_7b",
                name = "LFM 2.5 1.2B Instruct",
                fileName = "lfm2_5_1_7b.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/resolve/main/LFM2.5-1.2B-Instruct_int8.litertlm",
                sha256 = "", // 留空则跳过校验；填入官方 SHA-256 后下载完毕会自动校验
                sizeBytes = 1_288_490_189  // ~1.2GB
            ),
            ModelConfig(
                id = "qwen3_0_6b",
                name = "Qwen 3 0.6B",
                fileName = "qwen3_0_6b.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
                sha256 = "", // 留空则跳过校验；填入官方 SHA-256 后下载完毕会自动校验
                sizeBytes = 644_245_094  // ~600MB
            )
        )
    }

    init {
        if (!modelsDir.exists()) modelsDir.mkdirs()
        initializeDownloadStates()
    }

    /**
     * 初始化下载状态
     */
    private fun initializeDownloadStates() {
        val states = DEFAULT_MODELS.associate { config ->
            val file = File(modelsDir, config.fileName)
            val status = if (file.exists() && file.length() > 0) {
                DownloadStatus.COMPLETED
            } else {
                DownloadStatus.NOT_DOWNLOADED
            }
            config.id to ModelDownloadState(
                modelId = config.id,
                modelName = config.name,
                status = status,
                progress = if (status == DownloadStatus.COMPLETED) 1f else 0f,
                downloadedBytes = if (file.exists()) file.length() else 0,
                totalBytes = config.sizeBytes
            )
        }
        _downloadStates.value = states
    }

    /**
     * 检查是否所有模型都已下载
     */
    fun areAllModelsDownloaded(): Boolean {
        return _downloadStates.value.values.all { it.status == DownloadStatus.COMPLETED }
    }

    /**
     * 获取已下载的模型文件路径
     */
    fun getModelFilePath(modelId: String): String? {
        val config = DEFAULT_MODELS.find { it.id == modelId } ?: return null
        val file = File(modelsDir, config.fileName)
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    /**
     * 下载指定模型（流式写入磁盘，支持断点续传与 SHA-256 校验）。
     */
    suspend fun downloadModel(modelId: String): Boolean {
        val config = DEFAULT_MODELS.find { it.id == modelId } ?: return false
        val file = File(modelsDir, config.fileName)

        // 已下载完成
        if (file.exists() && file.length() > 0) {
            updateState(modelId, ModelDownloadState(
                modelId = modelId,
                modelName = config.name,
                status = DownloadStatus.COMPLETED,
                progress = 1f,
                downloadedBytes = file.length(),
                totalBytes = config.sizeBytes
            ))
            return true
        }

        val partFile = File(modelsDir, "${config.fileName}.part")
        var resumeFrom = if (partFile.exists()) partFile.length() else 0L

        _isDownloading.value = true
        val urls = DownloadMirrors.candidates(config.downloadUrl, config.mirrorUrls)
        var lastError = ""
        for ((index, url) in urls.withIndex()) {
            val switching = index > 0
            updateState(modelId, ModelDownloadState(
                modelId = modelId,
                modelName = config.name,
                status = DownloadStatus.DOWNLOADING,
                progress = if (config.sizeBytes > 0) resumeFrom.toFloat() / config.sizeBytes else 0f,
                downloadedBytes = resumeFrom,
                totalBytes = config.sizeBytes,
                errorMessage = if (switching) DownloadMirrors.SWITCHING else ""
            ))
            try {
                downloadFromUrl(config, file, partFile, resumeFrom, url)
                _isDownloading.value = false
                return true
            } catch (e: Exception) {
                lastError = e.message ?: "下载失败"
                resumeFrom = if (partFile.exists()) partFile.length() else 0L
                if (index < urls.lastIndex && DownloadMirrors.isTimeoutOrUnreachable(e)) {
                    continue
                }
                if (index < urls.lastIndex) continue
            }
        }
        updateState(modelId, ModelDownloadState(
            modelId = modelId,
            modelName = config.name,
            status = DownloadStatus.FAILED,
            downloadedBytes = if (partFile.exists()) partFile.length() else 0,
            totalBytes = config.sizeBytes,
            errorMessage = if (urls.size > 1) "${DownloadMirrors.ALL_FAILED}：$lastError" else lastError.ifBlank { "下载失败" }
        ))
        _isDownloading.value = false
        return false
    }

    private suspend fun downloadFromUrl(
        config: ModelConfig,
        file: File,
        partFile: File,
        resumeFrom: Long,
        url: String
    ) {
        httpClient.prepareGet(url) {
            if (resumeFrom > 0) header(HttpHeaders.Range, "bytes=$resumeFrom-")
        }.execute { response ->
            val append = resumeFrom > 0 && response.status == HttpStatusCode.PartialContent
            val startBytes = if (append) resumeFrom else 0L
            if (!append) partFile.delete()
            val totalLength = response.contentLength()?.let { it + startBytes } ?: config.sizeBytes

            val channel = response.bodyAsChannel()
            java.io.FileOutputStream(partFile, append).use { out ->
                val buffer = ByteArray(64 * 1024)
                var written = if (append) resumeFrom else 0L
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n == -1) break
                    if (n == 0) continue
                    out.write(buffer, 0, n)
                    written += n
                    updateState(config.id, ModelDownloadState(
                        modelId = config.id,
                        modelName = config.name,
                        status = DownloadStatus.DOWNLOADING,
                        progress = if (totalLength > 0) (written.toFloat() / totalLength).coerceIn(0f, 0.99f) else 0f,
                        downloadedBytes = written,
                        totalBytes = config.sizeBytes
                    ))
                }
            }
        }

        if (file.exists()) file.delete()
        if (!partFile.renameTo(file)) {
            partFile.copyTo(file, overwrite = true)
            partFile.delete()
        }

        updateState(config.id, ModelDownloadState(
            modelId = config.id,
            modelName = config.name,
            status = DownloadStatus.VERIFYING,
            progress = 1f,
            downloadedBytes = file.length(),
            totalBytes = config.sizeBytes
        ))

        val actualSha256 = calculateSha256(file)
        val expectedSha256 = config.sha256.trim().lowercase()
        if (expectedSha256.isNotEmpty() && actualSha256 != expectedSha256) {
            file.delete()
            throw IllegalStateException("SHA-256 校验失败：期望 $expectedSha256，实际 $actualSha256")
        }

        if (file.length() <= 1024) {
            file.delete()
            throw IllegalStateException("下载的文件大小异常")
        }
        updateState(config.id, ModelDownloadState(
            modelId = config.id,
            modelName = config.name,
            status = DownloadStatus.COMPLETED,
            progress = 1f,
            downloadedBytes = file.length(),
            totalBytes = config.sizeBytes
        ))
    }

    /**
     * 下载所有模型
     */
    suspend fun downloadAllModels(): Boolean {
        var allSuccess = true
        for (config in DEFAULT_MODELS) {
            val success = downloadModel(config.id)
            if (!success) allSuccess = false
        }
        return allSuccess
    }

    /**
     * 删除已下载的模型：删除文件（默认模型按配置文件名、导入模型按 modelId 命名的文件），
     * 并将下载状态重置为「未下载」。
     */
    fun deleteModel(modelId: String) {
        val config = getModelConfigs().find { it.id == modelId }
        val candidates = buildList {
            if (config != null) add(File(modelsDir, config.fileName))
            add(File(modelsDir, modelId))
            add(File(modelsDir, "$modelId.litertlm"))
        }
        candidates.filter { it.exists() }.forEach { it.delete() }
        updateState(modelId, ModelDownloadState(
            modelId = modelId,
            modelName = config?.name ?: modelId,
            status = DownloadStatus.NOT_DOWNLOADED,
            progress = 0f,
            downloadedBytes = 0,
            totalBytes = config?.sizeBytes ?: 0L
        ))
    }

    /**
     * 导入本地模型文件
     * 将用户选择的模型文件复制到模型目录并注册
     */
    fun importLocalModel(sourceFile: File, modelId: String, modelName: String): Boolean {
        return try {
            val fileName = sourceFile.name
            val targetFile = File(modelsDir, fileName)
            modelsDir.mkdirs()

            // 复制文件到模型目录
            sourceFile.copyTo(targetFile, overwrite = true)

            // 验证文件
            if (!targetFile.exists() || targetFile.length() == 0L) {
                targetFile.delete()
                return false
            }

            // 计算 SHA-256（可选，用于验证）
            val sha256 = calculateSha256(targetFile)

            // 注册到下载状态
            val state = ModelDownloadState(
                modelId = modelId,
                modelName = modelName,
                status = DownloadStatus.COMPLETED,
                progress = 1f,
                downloadedBytes = targetFile.length(),
                totalBytes = targetFile.length()
            )
            updateState(modelId, state)

            // 如果不在默认配置中，添加到配置列表（可选）
            // 注意：DEFAULT_MODELS 是 val，不可变，这里仅更新内存状态

            true
        } catch (e: Exception) {
            android.util.Log.e("ModelDownloadManager", "importLocalModel failed", e)
            false
        }
    }

    /**
     * 获取下载进度
     */
    fun getDownloadProgress(modelId: String): ModelDownloadState? {
        return _downloadStates.value[modelId]
    }

    /**
     * 获取所有模型配置
     */
    fun getModelConfigs(): List<ModelConfig> = DEFAULT_MODELS

    private fun updateState(modelId: String, state: ModelDownloadState) {
        val current = _downloadStates.value.toMutableMap()
        current[modelId] = state
        _downloadStates.value = current
    }

    /**
     * 计算 SHA-256 校验和
     */
    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

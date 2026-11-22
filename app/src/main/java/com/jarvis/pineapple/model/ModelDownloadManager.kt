package com.jarvis.pineapple.model

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    COMPLETED,
    FAILED,
    VERIFYING,
    CANCELLED
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
class ModelDownloadManager internal constructor(
    private val modelsDir: File,
    private val httpClient: HttpClient,
    private val configs: List<ModelConfig>
) {
    constructor(context: Context) : this(
        File(context.filesDir, "models"),
        HttpClient(Android) {
            // 错误页/拒绝响应不能进入模型文件写入流程。
            expectSuccess = true
            engine {
                connectTimeout = 15_000
                socketTimeout = 30_000
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        },
        DEFAULT_MODELS
    )

    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = _downloadStates.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val downloadLocks = ConcurrentHashMap<String, Mutex>()
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val activeDownloads = AtomicInteger()
    private val stateLock = Any()
    private val verificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        /** 四个默认模型配置；大小与 SHA-256 来自发布仓库的 LFS 元数据。 */
        val DEFAULT_MODELS = listOf(
            ModelConfig(
                id = "gemma4_e2b_2_5b",
                name = "Gemma 4 E2B 2.5B",
                fileName = "gemma4_e2b_2_5b.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
                sizeBytes = 2_588_147_712L
            ),
            ModelConfig(
                id = "gemma4_12b",
                name = "Gemma 4 12B",
                fileName = "gemma4_12b.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-12B-it-litert-lm/resolve/main/gemma-4-12B-it.litertlm",
                sha256 = "58fd31b778ca2c21c80d634fb34fc5a89d11d563a38dfd3cbf1b40dbf252a8b6",
                sizeBytes = 6_883_278_368L
            ),
            ModelConfig(
                id = "lfm2_5_1_7b",
                name = "LFM 2.5 1.2B Instruct",
                fileName = "lfm2_5_1_7b.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/resolve/main/LFM2.5-1.2B-Instruct_int8.litertlm",
                sha256 = "ed8201f88a1a116218bf03cf7cc5c33accdc2f6f502aa8d682f616beea1ec870",
                sizeBytes = 1_247_091_440L
            ),
            ModelConfig(
                id = "qwen3_0_6b",
                name = "Qwen 3 0.6B",
                fileName = "qwen3_0_6b.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
                sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
                sizeBytes = 613_722_264L
            )
        )

        /** 非空占位、错误页、截断文件及偏大的拼接文件都不能视为完成。 */
        internal fun isExpectedSize(config: ModelConfig, size: Long): Boolean =
            size > 1024 && (config.sizeBytes <= 0 || size == config.sizeBytes)

        internal fun failedDeletions(files: List<File>): List<File> =
            files.distinctBy { it.absolutePath }.filter { file ->
                if (!file.exists()) return@filter false
                try {
                    !file.delete()
                } catch (e: SecurityException) {
                    true
                }
            }
    }

    init {
        if (!modelsDir.exists()) modelsDir.mkdirs()
        initializeDownloadStates()
    }

    /**
     * 初始化下载状态
     */
    private fun initializeDownloadStates() {
        val states = configs.associate { config ->
            val file = File(modelsDir, config.fileName)
            val hasCandidate = isExpectedSize(config, if (file.exists()) file.length() else 0L)
            config.id to ModelDownloadState(
                modelId = config.id,
                modelName = config.name,
                status = if (hasCandidate) DownloadStatus.VERIFYING else DownloadStatus.NOT_DOWNLOADED,
                progress = if (hasCandidate) 1f else 0f,
                downloadedBytes = if (file.exists()) file.length() else 0,
                totalBytes = config.sizeBytes
            )
        }
        _downloadStates.value = states

        // 大小相同也不能证明内容完整；后台复核 SHA-256，避免阻塞主线程。
        verificationScope.launch {
            configs.forEach { config ->
                val file = File(modelsDir, config.fileName)
                if (!isExpectedSize(config, if (file.exists()) file.length() else 0L)) return@forEach
                downloadLocks.computeIfAbsent(config.id) { Mutex() }.withLock {
                    if (_downloadStates.value[config.id]?.status != DownloadStatus.VERIFYING) {
                        return@withLock
                    }
                    val verified = try {
                        config.sha256.isBlank() || calculateSha256(file).equals(config.sha256, ignoreCase = true)
                    } catch (e: Exception) {
                        false
                    }
                    updateState(config.id, ModelDownloadState(
                        modelId = config.id,
                        modelName = config.name,
                        status = if (verified) DownloadStatus.COMPLETED else DownloadStatus.FAILED,
                        progress = if (verified) 1f else 0f,
                        downloadedBytes = file.length(),
                        totalBytes = config.sizeBytes,
                        errorMessage = if (verified) "" else "已有模型文件 SHA-256 校验失败，请重新下载"
                    ))
                }
            }
        }
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
        val config = configs.find { it.id == modelId } ?: return null
        val file = File(modelsDir, config.fileName)
        return if (_downloadStates.value[modelId]?.status == DownloadStatus.COMPLETED &&
            isExpectedSize(config, if (file.exists()) file.length() else 0L)
        ) file.absolutePath else null
    }

    /**
     * 下载指定模型（流式写入磁盘，支持断点续传与 SHA-256 校验）。
     */
    suspend fun downloadModel(modelId: String): Boolean {
        val config = configs.find { it.id == modelId } ?: return false
        val lock = downloadLocks.computeIfAbsent(modelId) { Mutex() }
        return lock.withLock {
            val job = currentCoroutineContext()[Job]
            if (job != null) downloadJobs[modelId] = job
            activeDownloads.incrementAndGet()
            _isDownloading.value = true
            try {
                downloadModelLocked(config, job)
            } catch (e: CancellationException) {
                val partFile = File(modelsDir, "${config.fileName}.part")
                updateState(modelId, ModelDownloadState(
                    modelId = modelId,
                    modelName = config.name,
                    status = DownloadStatus.CANCELLED,
                    downloadedBytes = if (partFile.exists()) partFile.length() else 0,
                    totalBytes = config.sizeBytes,
                    errorMessage = e.message ?: "下载已取消"
                ))
                throw e
            } finally {
                if (job != null) downloadJobs.remove(modelId, job)
                _isDownloading.value = activeDownloads.decrementAndGet() > 0
            }
        }
    }

    /** 取消当前模型的活动下载；等待同模型锁的任务尚未开始，不会被误取消。 */
    fun cancelDownload(modelId: String): Boolean {
        val job = downloadJobs[modelId] ?: return false
        job.cancel(CancellationException("用户取消模型下载"))
        return true
    }

    private suspend fun downloadModelLocked(config: ModelConfig, job: Job?): Boolean {
        val modelId = config.id
        val file = File(modelsDir, config.fileName)

        // 已有文件必须通过大小与 SHA-256 复核；占位/截断文件继续重新下载。
        if (file.exists() && isExpectedSize(config, file.length()) &&
            (config.sha256.isBlank() ||
                calculateSha256(file) { job?.ensureActive() }.equals(config.sha256, ignoreCase = true))
        ) {
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
                downloadFromUrl(config, file, partFile, resumeFrom, url, job)
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: "下载失败"
                resumeFrom = if (partFile.exists()) partFile.length() else 0L
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
        return false
    }

    private suspend fun downloadFromUrl(
        config: ModelConfig,
        file: File,
        partFile: File,
        resumeFrom: Long,
        url: String,
        job: Job?
    ) {
        httpClient.prepareGet(url) {
            if (resumeFrom > 0) header(HttpHeaders.Range, "bytes=$resumeFrom-")
        }.execute { response ->
            if (response.status != HttpStatusCode.OK && response.status != HttpStatusCode.PartialContent) {
                throw IllegalStateException("模型下载 HTTP ${response.status.value}")
            }
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

        job?.ensureActive()
        if (file.exists() && !file.delete()) throw IllegalStateException("无法替换旧模型文件")
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

        val actualSize = file.length()
        if (!isExpectedSize(config, actualSize)) {
            file.delete()
            throw IllegalStateException("模型大小校验失败：期望 ${config.sizeBytes}，实际 $actualSize")
        }

        val actualSha256 = calculateSha256(file) { job?.ensureActive() }
        val expectedSha256 = config.sha256.trim().lowercase()
        if (expectedSha256.isNotEmpty() && actualSha256 != expectedSha256) {
            file.delete()
            throw IllegalStateException("SHA-256 校验失败：期望 $expectedSha256，实际 $actualSha256")
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
        for (config in configs) {
            val success = downloadModel(config.id)
            if (!success) allSuccess = false
        }
        return allSuccess
    }

    /**
     * 删除已下载的模型：删除文件（默认模型按配置文件名、导入模型按 modelId 命名的文件），
     * 并将下载状态重置为「未下载」。
     */
    fun deleteModel(modelId: String): Boolean {
        val config = getModelConfigs().find { it.id == modelId }
        val candidates = buildList {
            if (config != null) add(File(modelsDir, config.fileName))
            add(File(modelsDir, modelId))
            add(File(modelsDir, "$modelId.litertlm"))
        }
        if (failedDeletions(candidates).isNotEmpty()) return false
        updateState(modelId, ModelDownloadState(
            modelId = modelId,
            modelName = config?.name ?: modelId,
            status = DownloadStatus.NOT_DOWNLOADED,
            progress = 0f,
            downloadedBytes = 0,
            totalBytes = config?.sizeBytes ?: 0L
        ))
        return true
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
    fun getModelConfigs(): List<ModelConfig> = configs

    private fun updateState(modelId: String, state: ModelDownloadState) = synchronized(stateLock) {
        val current = _downloadStates.value.toMutableMap()
        current[modelId] = state
        _downloadStates.value = current
    }

    /**
     * 计算 SHA-256 校验和
     */
    private fun calculateSha256(file: File, checkpoint: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                checkpoint()
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

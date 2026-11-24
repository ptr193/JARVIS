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
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.InputStream
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
    EXTRACTING,
    COMPLETED,
    FAILED,
    VERIFYING,
    CANCELLED
}

/**
 * 模型下载管理器。
 *
 * 功能：
 * - 首次启动由用户选择模型；随包 7z 模型本地解压，其余模型联网下载
 * - 下载支持断点续传，解压与下载后均校验真实大小和 SHA-256
 * - 模型安装到应用私有目录 context.filesDir/models/
 *
 * 模型目录共 29 款，均来自 litert-community；其中 5 款随 APK 内置为两个压缩包
 * （推荐包 recommended.7z、次要包 secondary.7z），选中后直接本地解压；
 * 其余模型按 DownloadMirrors 的国内优先顺序联网获取。
 *
 * 下载地址按 [DownloadMirrors] 展开为「ModelScope → hf-mirror → HuggingFace 直连」，
 * 国内网络下优先走魔搭，避免 huggingface.co 被墙导致“下不了”。
 */
class ModelDownloadManager internal constructor(
    private val modelsDir: File,
    private val httpClient: HttpClient,
    private val configs: List<ModelConfig>,
    private val bundledAssetOpener: ((String) -> InputStream?)? = null,
    private val cacheDir: File = modelsDir
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
        DEFAULT_MODELS,
        bundledAssetOpener = { path -> context.assets.open(path) },
        cacheDir = context.cacheDir
    )

    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = _downloadStates.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val downloadLocks = ConcurrentHashMap<String, Mutex>()
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    // 同一压缩包内多款模型共享同一份缓存归档，按 assets 路径加锁，避免重复拷贝。
    private val archiveLocks = ConcurrentHashMap<String, Mutex>()
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
        val mirrorUrls: List<String> = emptyList(),
        val bundledAsset: String? = null,
        val archiveBytes: Long = 0
    ) {
        val isBundled: Boolean get() = bundledAsset != null
    }

    companion object {
        /** 随 APK 内置的推荐模型压缩包（assets 路径）。 */
        const val BUNDLE_RECOMMENDED = "models/recommended.7z"
        /** 随 APK 内置的次要推荐模型压缩包（assets 路径）。 */
        const val BUNDLE_SECONDARY = "models/secondary.7z"

        /** 默认模型配置；大小与 SHA-256 来自 litert-community 发布仓库的 LFS 元数据。 */
        val DEFAULT_MODELS = listOf(
            litertCommunityModel(
                "smollm2_135m_instruct", "SmolLM2 135M Instruct",
                "SmolLM2-135M-Instruct", "SmolLM2_135M_Instruct.litertlm",
                142_819_328L, "ccdc5c85735743f081b7d44ca309cab569f76c0f2f0e8e163449a63721969c37",
                bundledAsset = BUNDLE_RECOMMENDED, archiveBytes = 658_171_610L
            ),
            litertCommunityModel(
                "smollm2_360m_instruct", "SmolLM2 360M Instruct",
                "SmolLM2-360M-Instruct", "SmolLM2_360M_instruct.litertlm",
                373_719_040L, "8e2834da211b439751af968ed650febdde5a8cb8d88bc6c1a3059f049caa5c2e",
                bundledAsset = BUNDLE_SECONDARY, archiveBytes = 562_153_787L
            ),
            litertCommunityModel(
                "functiongemma_270m_mobile_actions", "FunctionGemma 270M Mobile Actions",
                "functiongemma-270m-ft-mobile-actions", "mobile_actions_q8_ekv1024.litertlm",
                288_964_608L, "33e295cbd996b419bb1de8f3f85c5b6b01ee058a2c89bdb2173cf3e6ff4ce9d0",
                bundledAsset = BUNDLE_SECONDARY, archiveBytes = 562_153_787L
            ),
            litertCommunityModel(
                "gemma3_270m_it", "Gemma 3 270M IT q8",
                "gemma-3-270m-it", "gemma3-270m-it-q8.litertlm",
                304_005_120L, "757e9119fa5bd667a2774fb470ac4afcd3190a21c677f8e69a5d6bc908abdd63",
                bundledAsset = BUNDLE_RECOMMENDED, archiveBytes = 658_171_610L
            ),
            litertCommunityModel(
                "qwen3_0_6b_int4", "Qwen 3 0.6B int4",
                "Qwen3-0.6B", "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
                343_490_560L, "bf1aba9ce8d963124407353c6aa4cbf72eba78db21ae133de983eac672ce83e0",
                bundledAsset = BUNDLE_RECOMMENDED, archiveBytes = 658_171_610L
            ),
            litertCommunityModel(
                "qwen3_0_6b", "Qwen 3 0.6B q8",
                "Qwen3-0.6B", "Qwen3-0.6B.litertlm",
                613_722_264L, "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108"
            ),
            litertCommunityModel(
                "qwen2_0_5b", "Qwen 2 0.5B Instruct",
                "Qwen2-0.5B-Instruct", "Qwen2_0.5B_Instruct.litertlm",
                647_377_840L, "0f01cc004b8eb62b92ba6be85ed05a248ba0d2f78af94c4949b313eccfb4c157"
            ),
            litertCommunityModel(
                "lfm2_5_1_2b_int4", "LFM 2.5 1.2B Instruct int4",
                "LFM2.5-1.2B-Instruct", "LFM2.5-1.2B-Instruct_int4.litertlm",
                736_015_744L, "e904729a8a8a0160658cb7cd22992e270a0547c05cc45b08c47ece5c0e29425b"
            ),
            litertCommunityModel(
                "minicpm5_1b_int4", "MiniCPM5 1B int4",
                "MiniCPM5-1B", "minicpm_wi4b32_wi8_afp32.litertlm",
                792_619_648L, "ee1b70697ef583c46d26df5772055946d4ca2a9a80b059149bb51f4ab9646863"
            ),
            litertCommunityModel(
                "olmo2_1b_int4", "OLMo 2 1B Instruct int4",
                "OLMo-2-1B-Instruct", "OLMo-2-1B-Instruct_q4_block32_ekv4096.litertlm",
                933_543_936L, "aa3a27afd2ed3fe7d3870bf53dc6fa04a191f98d5d10028979524de9fb64b117"
            ),
            litertCommunityModel(
                "qwen3_1_7b_int4", "Qwen 3 1.7B int4",
                "Qwen3-1.7B", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
                977_184_032L, "2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8"
            ),
            litertCommunityModel(
                "lfm2_5_1_7b", "LFM 2.5 1.2B Instruct int8",
                "LFM2.5-1.2B-Instruct", "LFM2.5-1.2B-Instruct_int8.litertlm",
                1_247_091_440L, "ed8201f88a1a116218bf03cf7cc5c33accdc2f6f502aa8d682f616beea1ec870"
            ),
            litertCommunityModel(
                "lfm3_1_7b_int4", "LFM 3 1.7B int4",
                "LFM3-1.7B-LiteRT", "LFM3-1.7B_mixed_int4_ekv4096.litertlm",
                1_441_693_696L, "662dd1c3d844081ccfaaac8ee26daf477788f08aedbc7588d52465296064de64"
            ),
            litertCommunityModel(
                "tiny_swallow_1_5b", "TinySwallow 1.5B Instruct",
                "TinySwallow-1.5B-Instruct", "TinySwallow-1.5B-Instruct.litertlm",
                1_567_604_736L, "a79dab105dca3474188cb2cd7f3e94380567dd10d788c830ca7f7388d4ce816b"
            ),
            litertCommunityModel(
                "vibe_thinker_1_5b", "VibeThinker 1.5B",
                "VibeThinker-1.5B", "VibeThinker-1.5B.litertlm",
                1_567_604_736L, "2b954abf0df11deb7bea9c4ce779566918dd81f4e578d673e7f618b10799e412"
            ),
            litertCommunityModel(
                "qwen2_5_1_5b", "Qwen 2.5 1.5B Instruct q8",
                "Qwen2.5-1.5B-Instruct", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                1_597_922_856L, "c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e"
            ),
            litertCommunityModel(
                "qwen3_1_7b", "Qwen 3 1.7B q8",
                "Qwen3-1.7B", "Qwen3_1.7B.litertlm",
                1_754_890_240L, "e40599625f3e446d6522674586a40566d6a3a117af608f6e9ea46ac86d326461"
            ),
            litertCommunityModel(
                "qwen2_1_5b", "Qwen 2 1.5B Instruct",
                "Qwen2-1.5B-Instruct", "Qwen2_1.5B_Instruct.litertlm",
                1_802_843_056L, "f0009d992f962157ee7164e7bf93d16229992fbb4ffe4ee055d0da47f189ad62"
            ),
            litertCommunityModel(
                "deepseek_r1_distill_qwen_1_5b", "DeepSeek R1 Distill Qwen 1.5B q8",
                "DeepSeek-R1-Distill-Qwen-1.5B", "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
                1_833_451_520L, "69b35f01759eed765641ab4af589bbe98131fd2825662a086d9037409b8c1295"
            ),
            litertCommunityModel(
                "smollm3_3b_int4", "SmolLM3 3B int4",
                "SmolLM3-3B", "SmolLM3-3B_q4_block32_ekv4096.litertlm",
                2_004_664_320L, "7ff3bf595a29cc8784d0cb6f68325922e70c2e5f3ac2e0050eb6a0bd5290468c"
            ),
            litertCommunityModel(
                "qwen3_4b_int4", "Qwen 3 4B int4",
                "Qwen3-4B", "qwen3_4b_mixed_int4.litertlm",
                2_272_935_936L, "4877b236f09c0b519958612afeb3c6fc6575c7ce56080d0d3a5406150e0ff9ce"
            ),
            litertCommunityModel(
                "ministral3_3b_int4", "Ministral 3 3B Instruct int4",
                "Ministral-3-3B-Instruct-2512", "Ministral-3-3B-Instruct-2512_q4_block32_ekv4096.litertlm",
                2_342_895_616L, "2e01bdbef438c1a57da21cfc9c040b271b563c0f77b8b7d1907a6154f962d0f1"
            ),
            litertCommunityModel(
                "gemma4_e2b_2_5b", "Gemma 4 E2B 2.5B",
                "gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm",
                2_588_147_712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
            ),
            litertCommunityModel(
                "qwen2_5_coder_3b", "Qwen 2.5 Coder 3B Instruct",
                "Qwen2.5-Coder-3B-Instruct", "Qwen2.5_Coder_3B_It.litertlm",
                3_433_083_824L, "78d23da074383f52f852b945b8090870e6c9dded02a842f535ee3ccb9e2874f3"
            ),
            litertCommunityModel(
                "phi4_mini_instruct", "Phi-4 Mini Instruct q8",
                "Phi-4-mini-instruct", "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                3_910_090_752L, "7764d4deb53800578307be33039476b38a6c370fff71bedb3c0552563e23ab02"
            ),
            litertCommunityModel(
                "deepseek_r1_distill_qwen_7b_int4", "DeepSeek R1 Distill Qwen 7B int4",
                "DeepSeek-R1-Distill-Qwen-7B", "DeepSeek-R1-Distill-Qwen-7B_q4_block32_ekv4096.litertlm",
                4_533_698_560L, "095880100e5af6261beab2f5d0ac8b79b48417714c862b0295e62f5da7a914ec"
            ),
            litertCommunityModel(
                "qwen3_8b_int4", "Qwen 3 8B int4",
                "Qwen3-8B", "qwen3_8b_mixed_int4.litertlm",
                4_887_412_736L, "cb4e6d0de4bbf6656d177812cf0c6a983967dedd17e7f88e84b901c3a9862a42"
            ),
            litertCommunityModel(
                "gemma4_12b", "Gemma 4 12B",
                "gemma-4-12B-it-litert-lm", "gemma-4-12B-it.litertlm",
                6_883_278_368L, "58fd31b778ca2c21c80d634fb34fc5a89d11d563a38dfd3cbf1b40dbf252a8b6"
            ),
            litertCommunityModel(
                "qwen3_14b_int4", "Qwen 3 14B int4",
                "Qwen3-14B", "qwen3_14b_mixed_int4.litertlm",
                8_655_863_808L, "71de7d58f1b46a3fcba2f7bb700ebcc3c3715877d9a7028d93dd1bcd89bbe946"
            )
        )

        private fun litertCommunityModel(
            id: String,
            name: String,
            repository: String,
            remoteFile: String,
            sizeBytes: Long,
            sha256: String,
            bundledAsset: String? = null,
            archiveBytes: Long = 0
        ) = ModelConfig(
            id = id,
            name = name,
            fileName = "$id.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/$repository/resolve/main/$remoteFile",
            sha256 = sha256,
            sizeBytes = sizeBytes,
            bundledAsset = bundledAsset,
            archiveBytes = archiveBytes
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
     * 安装指定模型：随包模型只从 APK assets 解压，其他模型走联网下载。
     */
    suspend fun installModel(modelId: String): Boolean {
        val config = configs.find { it.id == modelId } ?: return false
        if (!config.isBundled) return downloadModel(modelId)
        val lock = downloadLocks.computeIfAbsent(modelId) { Mutex() }
        return withContext(Dispatchers.IO) {
            lock.withLock {
                val job = currentCoroutineContext()[Job]
                if (job != null) downloadJobs[modelId] = job
                activeDownloads.incrementAndGet()
                _isDownloading.value = true
                try {
                    extractBundledModelLocked(config, job)
                } catch (e: CancellationException) {
                    updateState(modelId, ModelDownloadState(
                        modelId = modelId,
                        modelName = config.name,
                        status = DownloadStatus.CANCELLED,
                        totalBytes = config.sizeBytes,
                        errorMessage = e.message ?: "本地解压已取消"
                    ))
                    throw e
                } finally {
                    if (job != null) downloadJobs.remove(modelId, job)
                    _isDownloading.value = activeDownloads.decrementAndGet() > 0
                }
            }
        }
    }

    private suspend fun extractBundledModelLocked(config: ModelConfig, job: Job?): Boolean {
        val file = File(modelsDir, config.fileName)
        val staged = File(modelsDir, "${config.fileName}.extracting")
        val assetPath = config.bundledAsset ?: throw IllegalStateException("不是随包模型")
        // 缓存键按归档路径而非 modelId，使同一压缩包内的多款模型只拷贝一次。
        val archive = File(cacheDir, assetPath.replace('/', '_').replace('\\', '_'))
        try {
            if (file.exists() && isExpectedSize(config, file.length()) &&
                calculateSha256(file) { job?.ensureActive() }.equals(config.sha256, ignoreCase = true)
            ) {
                updateState(config.id, ModelDownloadState(
                    modelId = config.id,
                    modelName = config.name,
                    status = DownloadStatus.COMPLETED,
                    progress = 1f,
                    downloadedBytes = file.length(),
                    totalBytes = config.sizeBytes
                ))
                return true
            }
            if ((!modelsDir.isDirectory && !modelsDir.mkdirs()) ||
                (!cacheDir.isDirectory && !cacheDir.mkdirs())
            ) {
                throw IllegalStateException("模型目录或临时目录不可用")
            }
            if (staged.exists() && !staged.delete()) {
                throw IllegalStateException("无法清理上次解压的临时文件")
            }
            updateState(config.id, ModelDownloadState(
                modelId = config.id,
                modelName = config.name,
                status = DownloadStatus.EXTRACTING,
                totalBytes = config.sizeBytes
            ))

            val opener = bundledAssetOpener ?: throw IllegalStateException("安装包未提供模型 assets 读取器")
            archiveLocks.computeIfAbsent(assetPath) { Mutex() }.withLock {
                if (archive.exists() && config.archiveBytes > 0 && archive.length() != config.archiveBytes) {
                    archive.delete()
                }
                if (!archive.exists()) {
                    val input = opener(assetPath) ?: throw IllegalStateException("APK 中缺少 $assetPath")
                    input.use { source ->
                        archive.outputStream().use { output -> source.copyTo(output) }
                    }
                }
            }
            job?.ensureActive()
            if (config.archiveBytes > 0 && archive.length() != config.archiveBytes) {
                throw IllegalStateException(
                    "内置压缩包大小校验失败：期望 ${config.archiveBytes}，实际 ${archive.length()}"
                )
            }

            var found = false
            SevenZFile(archive).use { sevenZ ->
                while (true) {
                    val entry = sevenZ.nextEntry ?: break
                    // 只接受配置指定的单一文件名，绝不把归档路径拼接为输出路径。
                    if (entry.name != config.fileName) continue
                    if (entry.isDirectory) throw IllegalStateException("内置模型条目是目录")
                    var written = 0L
                    var lastPercent = -1
                    staged.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            job?.ensureActive()
                            val n = sevenZ.read(buffer, 0, buffer.size)
                            if (n < 0) break
                            if (n == 0) continue
                            output.write(buffer, 0, n)
                            written += n
                            if (config.sizeBytes > 0 && written > config.sizeBytes) {
                                throw IllegalStateException("解压结果超过官方模型大小")
                            }
                            val progress = if (config.sizeBytes > 0) {
                                (written.toFloat() / config.sizeBytes).coerceIn(0f, 0.99f)
                            } else 0f
                            val percent = (progress * 100).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                updateState(config.id, ModelDownloadState(
                                    modelId = config.id,
                                    modelName = config.name,
                                    status = DownloadStatus.EXTRACTING,
                                    progress = progress,
                                    downloadedBytes = written,
                                    totalBytes = config.sizeBytes
                                ))
                            }
                        }
                    }
                    found = true
                    break
                }
            }
            if (!found) throw IllegalStateException("7z 中缺少 ${config.fileName}")
            if (!isExpectedSize(config, staged.length())) {
                throw IllegalStateException("解压后大小校验失败：期望 ${config.sizeBytes}，实际 ${staged.length()}")
            }
            updateState(config.id, ModelDownloadState(
                modelId = config.id,
                modelName = config.name,
                status = DownloadStatus.VERIFYING,
                progress = 1f,
                downloadedBytes = staged.length(),
                totalBytes = config.sizeBytes
            ))
            val actualSha256 = calculateSha256(staged) { job?.ensureActive() }
            if (!actualSha256.equals(config.sha256, ignoreCase = true)) {
                throw IllegalStateException("解压后 SHA-256 校验失败：$actualSha256")
            }
            if (file.exists() && !file.delete()) throw IllegalStateException("无法替换旧模型文件")
            if (!staged.renameTo(file)) throw IllegalStateException("模型文件原子落库失败")
            updateState(config.id, ModelDownloadState(
                modelId = config.id,
                modelName = config.name,
                status = DownloadStatus.COMPLETED,
                progress = 1f,
                downloadedBytes = file.length(),
                totalBytes = config.sizeBytes
            ))
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 失败时清掉缓存归档，下次重试会重新从 APK 拷贝；成功则保留以便同包其它模型复用。
            archive.delete()
            updateState(config.id, ModelDownloadState(
                modelId = config.id,
                modelName = config.name,
                status = DownloadStatus.FAILED,
                totalBytes = config.sizeBytes,
                errorMessage = "本地模型解压失败：${e.message ?: e.javaClass.simpleName}"
            ))
            return false
        } finally {
            staged.delete()
        }
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
        if (!modelsDir.isDirectory && !modelsDir.mkdirs()) {
            throw IllegalStateException("模型目录不可用：${modelsDir.absolutePath}")
        }
        // 残留分片不小于目标大小时续传会一直得到 416，直接重置后重下。
        var start = resumeFrom
        if (config.sizeBytes in 1..start) {
            partFile.delete()
            start = 0L
        }
        httpClient.prepareGet(url) {
            header(HttpHeaders.AcceptEncoding, "identity")
            if (start > 0) header(HttpHeaders.Range, "bytes=$start-")
        }.execute { response ->
            if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                partFile.delete()
                throw IllegalStateException("断点续传位置无效（HTTP 416），已重置分片")
            }
            if (response.status != HttpStatusCode.OK && response.status != HttpStatusCode.PartialContent) {
                throw IllegalStateException("模型下载 HTTP ${response.status.value}")
            }
            val append = start > 0 && response.status == HttpStatusCode.PartialContent
            val startBytes = if (append) start else 0L
            if (!append) partFile.delete()
            val totalLength = response.contentLength()?.let { it + startBytes } ?: config.sizeBytes

            val channel = response.bodyAsChannel()
            java.io.FileOutputStream(partFile, append).use { out ->
                val buffer = ByteArray(64 * 1024)
                var written = if (append) resumeFrom else 0L
                var lastPercent = -1
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n == -1) break
                    if (n == 0) continue
                    out.write(buffer, 0, n)
                    written += n
                    val progress =
                        if (totalLength > 0) (written.toFloat() / totalLength).coerceIn(0f, 0.99f) else 0f
                    val percent = (progress * 100).toInt()
                    // 仅在百分比变化时刷新状态，避免每个 64KB 分块都触发 UI 重组。
                    if (percent != lastPercent) {
                        lastPercent = percent
                        updateState(config.id, ModelDownloadState(
                            modelId = config.id,
                            modelName = config.name,
                            status = DownloadStatus.DOWNLOADING,
                            progress = progress,
                            downloadedBytes = written,
                            totalBytes = config.sizeBytes
                        ))
                    }
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
            val success = installModel(config.id)
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

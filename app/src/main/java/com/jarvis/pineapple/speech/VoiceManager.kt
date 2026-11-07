package com.jarvis.pineapple.speech

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.jarvis.pineapple.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.client.plugins.onDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * 声线信息 - JARVIS 男声
 */
data class VoicePack(
    val id: String,
    val name: String,
    val language: String,
    val gender: String = "male",
    val description: String = "",
    val remoteUrl: String = "",
    val localPath: String = "",
    val sizeBytes: Long = 0,
    val checksumSha256: String = "",
    val isDownloaded: Boolean = false,
    val isDefault: Boolean = false,
    val modelType: String = "onnx", // "onnx" | "piper" | "system"
    val configJson: String = ""    // ONNX 模型配置 JSON
)

/**
 * 默认声线管理器（REQ-09）。
 * 支持 Piper ONNX 男声模型，从 assets 加载。
 * 语音模型下载地址通过 BuildConfig.VOICE_DOWNLOAD_BASE_URL 配置。
 */
class VoiceManager(private val context: Context) {

    private val voicesDir = File(context.filesDir, "voices")

    /**
     * ONNX 运行时环境。
     *
     * 必须惰性初始化：`OrtEnvironment.getEnvironment()` 会立即加载
     * `libonnxruntime.so`，若在构造时执行，则任何解析到 VoiceManager 的界面
     * （设置 → 语音）都会无条件加载 15MB+ 原生库。在部分真机上该 .so 加载
     * 失败会抛 `UnsatisfiedLinkError`（属 Error，常规 catch 不到），
     * 直接导致闪退。改为 isInitialized 保护的惰性单例，仅在真正需要推理时才加载。
     */
    private val ortEnvironment: OrtEnvironment? by lazy {
        runCatching { OrtEnvironment.getEnvironment() }.getOrNull()
    }

    private val _voices = MutableStateFlow<List<VoicePack>>(emptyList())
    val voices: StateFlow<List<VoicePack>> = _voices.asStateFlow()

    private val _activeVoice = MutableStateFlow<VoicePack?>(null)
    val activeVoice: StateFlow<VoicePack?> = _activeVoice.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _fallbackNotice = MutableStateFlow<String?>(null)
    val fallbackNotice: StateFlow<String?> = _fallbackNotice.asStateFlow()

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation)
    }

    init {
        if (!voicesDir.exists()) voicesDir.mkdirs()
        loadVoices()
    }

    /**
     * 获取声线库 - 全部为男声
     */
    fun getVoiceLibrary(): List<VoicePack> {
        val baseUrl = BuildConfig.VOICE_DOWNLOAD_BASE_URL
        return listOf(
            VoicePack(
                id = VoicePolicy.DEFAULT_ID,
                name = "JARVIS 中文声线",
                language = "zh-CN",
                gender = VoicePolicy.DEFAULT_GENDER,
                description = VoicePolicy.DEFAULT_DESCRIPTION,
                remoteUrl = "${baseUrl}zh_CN-chaowen-medium.onnx",
                sizeBytes = 63_221_984,
                isDefault = true,
                modelType = "onnx",
                configJson = "zh_CN-chaowen-medium.onnx.json"
            ),
            VoicePack(
                id = "jarvis_en_us",
                name = "JARVIS English",
                language = "en-US",
                gender = "male",
                description = "Original JARVIS English voice - deep, calm British butler tone",
                remoteUrl = "${baseUrl}en_US-ryan-medium.onnx",
                sizeBytes = 63_201_294,
                modelType = "onnx",
                configJson = "en_US-ryan-medium.onnx.json"
            ),
            VoicePack(
                id = "jarvis_zh_cn_alt",
                name = "JARVIS 中文声线 (备用)",
                language = "zh-CN",
                gender = "male",
                description = "备用中文男声，音色略有不同",
                remoteUrl = "${baseUrl}zh_CN-huayan-medium.onnx",
                sizeBytes = 63_201_294,
                modelType = "onnx",
                configJson = "zh_CN-huayan-medium.onnx.json"
            )
        )
    }

    /**
     * 从 assets 加载 ONNX 男声模型
     */
    suspend fun downloadVoice(voicePack: VoicePack): Boolean {
        if (voicePack.remoteUrl.isBlank()) {
            Log.w("VoiceManager", "voicePack.remoteUrl is blank for ${voicePack.id}")
            return false
        }

        _isDownloading.value = true
        _downloadProgress.value = 0f

        return withContext(Dispatchers.IO) {
            try {
                val targetFile = File(voicesDir, "${voicePack.id}.onnx")

                // 已存在且非空，直接返回
                if (targetFile.exists() && targetFile.length() > 0) {
                    _downloadProgress.value = 1f
                    _isDownloading.value = false
                    updateVoiceDownloaded(voicePack.id, targetFile.absolutePath)
                    return@withContext true
                }

                // 本地 assets 模式
                if (voicePack.remoteUrl.startsWith("file://")) {
                    val assetPath = voicePack.remoteUrl.removePrefix("file:///android_asset/")
                    val bytes = context.assets.open(assetPath).use { it.readBytes() }
                    targetFile.writeBytes(bytes)
                    _downloadProgress.value = 1f
                    _isDownloading.value = false
                    updateVoiceDownloaded(voicePack.id, targetFile.absolutePath)
                    return@withContext true
                }

                // 网络下载模式
                val totalBytes = AtomicLong(voicePack.sizeBytes)
                val downloadedBytes = AtomicLong(0L)

                val response = httpClient.get(voicePack.remoteUrl) {
                    onDownload { bytesRead, contentLength ->
                        downloadedBytes.set(bytesRead)
                        contentLength?.let { totalBytes.set(it) }
                        if (contentLength != null && contentLength > 0) {
                            _downloadProgress.value = bytesRead.toFloat() / contentLength
                        }
                    }
                }

                val bytes = response.readBytes()
                targetFile.writeBytes(bytes)

                // SHA-256 校验
                if (voicePack.checksumSha256.isNotBlank()) {
                    val actualSha256 = calculateSha256(targetFile)
                    if (actualSha256 != voicePack.checksumSha256.lowercase()) {
                        Log.e("VoiceManager", "SHA-256 mismatch for ${voicePack.id}")
                        targetFile.delete()
                        _isDownloading.value = false
                        return@withContext false
                    }
                }

                _downloadProgress.value = 1f
                _isDownloading.value = false
                updateVoiceDownloaded(voicePack.id, targetFile.absolutePath)
                true

            } catch (e: Exception) {
                Log.e("VoiceManager", "downloadVoice failed for ${voicePack.id}", e)
                _isDownloading.value = false
                _fallbackNotice.value = VoicePolicy.SOURCE_UNAVAILABLE
                false
            }
        }
    }

    /**
     * 使用 Piper ONNX 模型合成语音
     * Piper 推理链：text → phonemize → ONNX inference → WAV bytes
     */
    suspend fun synthesizeSpeech(voicePack: VoicePack, text: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                // 原生库可能加载失败（部分真机 ABI/ROM 限制），此时直接返回失败而非崩溃
                val env = ortEnvironment ?: run {
                    Log.e("VoiceManager", "ONNX Runtime 环境不可用，无法合成语音")
                    return@withContext null
                }
                val modelFile = File(voicePack.localPath)
                if (!modelFile.exists()) {
                    Log.e("VoiceManager", "Model file not found: ${voicePack.localPath}")
                    return@withContext null
                }

                // 读取配置 JSON
                val configFile = File(voicesDir, voicePack.configJson)
                val config = if (configFile.exists()) {
                    Json.parseToJsonElement(configFile.readText()).jsonObject
                } else {
                    return@withContext null
                }

                val sampleRate = config?.get("audio")?.jsonObject?.get("sample_rate")?.jsonPrimitive?.int ?: 22050

                // 创建 ONNX Runtime 会话
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val session = env.createSession(modelFile.absolutePath, sessionOptions)

                // 1. 文本 → 音素 IDs
                val phonemeIdMapStr = config?.get("phoneme_id_map")?.jsonObject?.let { jsonMap ->
                    jsonMap.entries.associate { (k, v) ->
                        runCatching { v.jsonPrimitive.contentOrNull ?: "" }.getOrDefault("") to
                        runCatching { v.jsonPrimitive.intOrNull ?: 0 }.getOrDefault(0)
                    }
                } ?: emptyMap()

                val phonemeIds = Phonemizer.textToPhonemeIds(text, voicePack.language, phonemeIdMapStr)

                if (phonemeIds.isEmpty()) {
                    Log.w("VoiceManager", "phonemeIds is empty for text: $text")
                    return@withContext null
                }

                // 2. ONNX 推理
                val inputBuffer = FloatBuffer.allocate(phonemeIds.size)
                phonemeIds.forEach { inputBuffer.put(it.toFloat()) }
                inputBuffer.rewind()

                val inputTensor = OnnxTensor.createTensor(
                    env,
                    inputBuffer,
                    longArrayOf(1, phonemeIds.size.toLong())
                )

                val inputName = session.inputNames.first()
                val results = session.run(mapOf(inputName to inputTensor))

                // 3. 提取音频输出
                val audioOutput = results.get(0)?.value as? Array<*>
                val audioData = mutableListOf<Short>()

                audioOutput?.forEach { frame ->
                    when (frame) {
                        is FloatArray -> frame.forEach { sample ->
                            audioData.add((sample * Short.MAX_VALUE).toInt().toShort())
                        }
                        is FloatBuffer -> while (frame.hasRemaining()) {
                            audioData.add((frame.get() * Short.MAX_VALUE).toInt().toShort())
                        }
                    }
                }

                results.close()
                inputTensor.close()

                if (audioData.isEmpty()) {
                    Log.w("VoiceManager", "ONNX inference returned empty audio")
                    return@withContext null
                }

                // 4. 转换为 WAV 字节流
                return@withContext wavBytesFromPcm(audioData.toShortArray(), sampleRate)

            } catch (e: Exception) {
                Log.e("VoiceManager", "synthesizeSpeech failed: ${e.message}", e)
                null
            }
        }
    }

    /**
     * PCM 16-bit 音频 → WAV 字节流
     */
    private fun wavBytesFromPcm(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = pcm.size * 2
        val totalSize = 36 + dataSize
        val buffer = java.nio.ByteBuffer.allocate(totalSize + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalSize)
        buffer.put("WAVE".toByteArray())

        // fmt chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // fmt chunk size
        buffer.putShort(1) // PCM format
        buffer.putShort(1) // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2) // byte rate (16-bit mono)
        buffer.putShort(2) // block align
        buffer.putShort(16) // bits per sample

        // data chunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        pcm.forEach { buffer.putShort(it) }

        return buffer.array()
    }

    /**
     * 设置当前激活的声线
     */
    fun setActiveVoice(voiceId: String) {
        val voice = _voices.value.firstOrNull { it.id == voiceId && it.isDownloaded }
        if (voice != null) {
            _activeVoice.value = voice
        }
    }

    /**
     * 删除声线
     */
    fun deleteVoice(voiceId: String) {
        val voice = _voices.value.firstOrNull { it.id == voiceId }
        if (voice != null) {
            File(voice.localPath).delete()
            _voices.value = _voices.value.map {
                if (it.id == voiceId) it.copy(isDownloaded = false, localPath = "") else it
            }
            if (_activeVoice.value?.id == voiceId) {
                _activeVoice.value = null
            }
        }
    }

    /**
     * 恢复默认声线
     */
    fun restoreDefault() {
        val default = _voices.value.firstOrNull { it.isDefault }
        if (default != null) {
            _activeVoice.value = default
        }
    }

    /**
     * 获取降级声线（系统 TTS）
     */
    fun getFallbackVoice(): VoicePack = VoicePolicy.fallbackVoice()

    fun resolveSpeakVoice(): VoicePack {
        val resolved = VoicePolicy.resolveActive(
            preferred = _activeVoice.value,
            downloaded = _voices.value.filter { it.isDownloaded }
        )
        _fallbackNotice.value = resolved.notice
        if (resolved.usingFallback) {
            _activeVoice.value = resolved.voice
        }
        return resolved.voice
    }

    fun consumeFallbackNotice(): String? {
        val notice = _fallbackNotice.value
        _fallbackNotice.value = null
        return notice
    }

    private fun updateVoiceDownloaded(voiceId: String, localPath: String) {
        _voices.value = _voices.value.map {
            if (it.id == voiceId) it.copy(isDownloaded = true, localPath = localPath) else it
        }
    }

    private fun loadVoices() {
        val library = getVoiceLibrary()
        val downloaded = library.map { voice ->
            val file = File(voicesDir, "${voice.id}.onnx")
            if (file.exists() && file.length() > 0) {
                voice.copy(isDownloaded = true, localPath = file.absolutePath)
            } else voice
        }
        _voices.value = downloaded
        _activeVoice.value = downloaded.firstOrNull { it.isDefault && it.isDownloaded }
            ?: downloaded.firstOrNull { it.isDownloaded }
    }

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

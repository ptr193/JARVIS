package com.jarvis.pineapple.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 默认钢铁侠中文男声策略。源失败时降级系统中文 TTS 并给出中文提示。
 */
object VoicePolicy {
    const val DEFAULT_ID = "jarvis_zh_cn"
    const val DEFAULT_GENDER = "male"
    const val DEFAULT_LANGUAGE = "zh-CN"
    const val DEFAULT_DESCRIPTION = "《钢铁侠》中文版 JARVIS 声线，低沉、平稳、略带电子质感的英式管家腔调"
    const val SOURCE_UNAVAILABLE = "声线源不可达，已降级系统中文 TTS"
    const val FALLBACK_ID = "fallback_system"

    /** 由模型地址推导同目录的 Piper 配置地址，禁止静默省略配置文件。 */
    fun configUrl(remoteUrl: String, configJson: String): String {
        require(configJson.isNotBlank()) { "声线配置文件名为空" }
        val base = remoteUrl.substringBeforeLast('/', "")
        require(base.isNotBlank()) { "声线模型地址无效" }
        return "$base/$configJson"
    }

    /** 网络边界校验：空模型、错误页/占位模型及无效配置均不能标记为下载成功。 */
    fun validateDownloadedPackage(modelBytes: ByteArray, configText: String) {
        require(modelBytes.size > 1024) { "声线模型为空或体积异常" }
        val config = Json.parseToJsonElement(configText).jsonObject
        val sampleRate = config["audio"]?.jsonObject
            ?.get("sample_rate")?.jsonPrimitive?.intOrNull
        require(sampleRate != null && sampleRate in 8_000..192_000) { "声线配置缺少有效采样率" }
    }

    fun fallbackVoice(): VoicePack = VoicePack(
        id = FALLBACK_ID,
        name = "系统默认声线",
        language = DEFAULT_LANGUAGE,
        gender = DEFAULT_GENDER,
        description = "系统 TTS 降级声线，保证基本语音输出",
        modelType = "system"
    )

    data class ResolvedVoice(
        val voice: VoicePack,
        val usingFallback: Boolean,
        val notice: String?
    )

    fun resolveActive(preferred: VoicePack?, downloaded: List<VoicePack>): ResolvedVoice {
        val defaultReady = downloaded.firstOrNull { it.id == DEFAULT_ID && it.isDownloaded }
        if (defaultReady != null && preferred?.id == DEFAULT_ID) {
            return ResolvedVoice(defaultReady, usingFallback = false, notice = null)
        }
        if (preferred != null && preferred.isDownloaded && preferred.modelType != "system") {
            return ResolvedVoice(preferred, usingFallback = false, notice = null)
        }
        if (defaultReady != null) {
            return ResolvedVoice(defaultReady, usingFallback = false, notice = null)
        }
        val anyReady = downloaded.firstOrNull { it.isDownloaded && it.modelType != "system" }
        if (anyReady != null) {
            return ResolvedVoice(anyReady, usingFallback = false, notice = null)
        }
        return ResolvedVoice(fallbackVoice(), usingFallback = true, notice = SOURCE_UNAVAILABLE)
    }
}

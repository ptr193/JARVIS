package com.jarvis.pineapple.net

/**
 * 主源失败后改写为国内镜像。HuggingFace → hf-mirror，GitHub → ghproxy。
 */
object DownloadMirrors {
    const val HF_MIRROR_HOST = "hf-mirror.com"
    const val GH_PROXY = "https://ghproxy.net/"
    const val SWITCHING = "主源超时，正在切换国内镜像"
    const val ALL_FAILED = "全部镜像均失败"

    fun hfMirror(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.contains("huggingface.co")) return null
        return trimmed
            .replace("https://huggingface.co", "https://$HF_MIRROR_HOST")
            .replace("http://huggingface.co", "https://$HF_MIRROR_HOST")
    }

    fun ghProxy(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(GH_PROXY)) return null
        val github = trimmed.startsWith("https://github.com/") ||
            trimmed.startsWith("http://github.com/") ||
            trimmed.startsWith("https://raw.githubusercontent.com/") ||
            trimmed.startsWith("https://codeload.github.com/")
        if (!github) return null
        val https = if (trimmed.startsWith("http://")) trimmed.replaceFirst("http://", "https://") else trimmed
        return GH_PROXY + https
    }

    fun expand(url: String): List<String> {
        val result = linkedSetOf<String>()
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) result += trimmed
        hfMirror(trimmed)?.let { result += it }
        ghProxy(trimmed)?.let { result += it }
        return result.toList()
    }

    fun candidates(primary: String, extra: List<String> = emptyList()): List<String> =
        (expand(primary) + extra.map { it.trim() }).filter { it.isNotBlank() }.distinct()

    fun isTimeoutOrUnreachable(error: Throwable): Boolean {
        val message = (error.message ?: "").lowercase()
        return error is java.net.SocketTimeoutException ||
            error is java.net.ConnectException ||
            error is java.net.UnknownHostException ||
            message.contains("timeout") ||
            message.contains("timed out") ||
            message.contains("failed to connect") ||
            message.contains("unable to resolve") ||
            message.contains("connection reset")
    }

    fun parseSha256Sidecar(text: String): String? {
        val match = Regex("[0-9a-fA-F]{64}").find(text.trim()) ?: return null
        return match.value.lowercase()
    }
}

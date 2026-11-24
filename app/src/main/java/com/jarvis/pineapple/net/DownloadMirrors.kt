package com.jarvis.pineapple.net

/**
 * 国内镜像改写。
 *
 * 中国大陆网络直连 huggingface.co / github.com 经常超时，因此候选地址按“国内优先、直连兜底”排列：
 * - HuggingFace → ModelScope（魔搭，国内）→ hf-mirror.com → huggingface.co
 * - GitHub → ghproxy.net → github.com
 *
 * 调用方按顺序尝试，任一地址成功即停止。
 */
object DownloadMirrors {
    const val HF_MIRROR_HOST = "hf-mirror.com"
    const val MODELSCOPE_ORIGIN = "https://modelscope.cn"
    const val GH_PROXY = "https://ghproxy.net/"
    const val SWITCHING = "主源超时，正在切换国内镜像"
    const val ALL_FAILED = "全部镜像均失败"

    fun isHuggingFace(url: String): Boolean =
        url.contains("huggingface.co")

    fun hfMirror(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (!isHuggingFace(trimmed)) return null
        return trimmed
            .replace("https://huggingface.co", "https://$HF_MIRROR_HOST")
            .replace("http://huggingface.co", "https://$HF_MIRROR_HOST")
    }

    /**
     * 将 HuggingFace 下载地址改写为 ModelScope（魔搭）地址：
     * `https://huggingface.co/{repo}/resolve/{rev}/{file}` →
     * `https://modelscope.cn/models/{repo}/resolve/master/{file}`
     *
     * ModelScope 只镜像了部分仓库；未镜像时返回 404，调用方会继续尝试后续地址。
     */
    fun modelScope(url: String): String? {
        val trimmed = url.trim()
        if (!isHuggingFace(trimmed)) return null
        val path = trimmed.substringAfter("huggingface.co", "").trimStart('/')
        val parts = path.split('/').filter { it.isNotEmpty() }
        val resolveIndex = parts.indexOf("resolve")
        // resolve 后紧跟 revision（main/master/...），文件名从 revision 之后开始。
        if (resolveIndex < 2 || resolveIndex + 2 >= parts.size) return null
        val repo = parts.subList(0, resolveIndex).joinToString("/")
        val file = parts.subList(resolveIndex + 2, parts.size).joinToString("/")
        if (repo.isBlank() || file.isBlank()) return null
        return "$MODELSCOPE_ORIGIN/models/$repo/resolve/master/$file"
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
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return emptyList()
        val result = linkedSetOf<String>()
        modelScope(trimmed)?.let { result += it }
        hfMirror(trimmed)?.let { result += it }
        ghProxy(trimmed)?.let { result += it }
        result += trimmed
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

    fun parseMd5Sidecar(text: String): String? {
        val match = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{32}(?![0-9a-fA-F])").find(text.trim()) ?: return null
        return match.value.lowercase()
    }
}

package com.jarvis.pineapple.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadMirrorsTest {

    @Test
    fun huggingfaceUrlRewritesToHfMirrorKeepingPath() {
        val src = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm"
        val mirrored = DownloadMirrors.hfMirror(src)
        assertEquals(
            "https://hf-mirror.com/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            mirrored
        )
        val urls = DownloadMirrors.candidates(src)
        // 国内镜像优先，直连兜底：ModelScope → hf-mirror → huggingface.co
        assertEquals(
            "https://modelscope.cn/models/litert-community/Qwen3-0.6B/resolve/master/Qwen3-0.6B.litertlm",
            urls[0]
        )
        assertEquals(mirrored, urls[1])
        assertEquals(src, urls[2])
        assertTrue(urls.size >= 3)
    }

    @Test
    fun modelScopeRewriteKeepsRepoAndFile() {
        assertEquals(
            "https://modelscope.cn/models/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/master/" +
                "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            DownloadMirrors.modelScope(
                "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/" +
                    "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm"
            )
        )
        assertNull(DownloadMirrors.modelScope("https://dl-cdn.alpinelinux.org/a.tar.gz"))
    }

    @Test
    fun githubUrlPrefixedWithGhproxy() {
        val src = "https://github.com/termux/proot-distro/releases/download/v4.27.0/debian-bookworm-aarch64-pd-v4.27.0.tar.xz"
        val proxied = DownloadMirrors.ghProxy(src)
        assertEquals("https://ghproxy.net/$src", proxied)
        val urls = DownloadMirrors.candidates(src)
        // GitHub 直连在国内不稳定，代理优先。
        assertTrue(urls[0].startsWith("https://ghproxy.net/https://github.com/"))
        assertEquals(src, urls[1])
    }

    @Test
    fun extraMirrorsAreAppendedAfterDerived() {
        val src = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz"
        val extra = "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz"
        val urls = DownloadMirrors.candidates(src, listOf(extra))
        assertEquals(src, urls[0])
        assertEquals(extra, urls[1])
        assertNull(DownloadMirrors.hfMirror(src))
        assertNull(DownloadMirrors.ghProxy(src))
    }

    @Test
    fun sidecarParserReadsFirstSha256Hex() {
        val text = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2  ubuntu-base-24.04.5-base-arm64.tar.gz\n"
        assertEquals(
            "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
            DownloadMirrors.parseSha256Sidecar(text)
        )
        assertNull(DownloadMirrors.parseSha256Sidecar("not-a-hash"))
    }

    @Test
    fun md5SidecarParserAcceptsArchFormat() {
        val text = "d41d8cd98f00b204e9800998ecf8427e  ArchLinuxARM-aarch64-latest.tar.gz\n"
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", DownloadMirrors.parseMd5Sidecar(text))
        assertNull(DownloadMirrors.parseMd5Sidecar("no checksum here"))
    }

    @Test
    fun timeoutClassifierCoversSocketAndConnect() {
        assertTrue(DownloadMirrors.isTimeoutOrUnreachable(java.net.SocketTimeoutException("Read timed out")))
        assertTrue(DownloadMirrors.isTimeoutOrUnreachable(java.net.ConnectException("failed to connect")))
        assertTrue(DownloadMirrors.isTimeoutOrUnreachable(IllegalStateException("timeout")))
    }

    @Test
    fun failoverCopyIsChineseAndStable() {
        assertEquals("主源超时，正在切换国内镜像", DownloadMirrors.SWITCHING)
        assertEquals("全部镜像均失败", DownloadMirrors.ALL_FAILED)
    }
}

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
        assertEquals(src, urls[0])
        assertEquals(mirrored, urls[1])
        assertTrue(urls.size >= 2)
    }

    @Test
    fun githubUrlPrefixedWithGhproxy() {
        val src = "https://github.com/termux/proot-distro/releases/download/v4.27.0/debian-bookworm-aarch64-pd-v4.27.0.tar.xz"
        val proxied = DownloadMirrors.ghProxy(src)
        assertEquals("https://ghproxy.net/$src", proxied)
        val urls = DownloadMirrors.candidates(src)
        assertEquals(src, urls[0])
        assertTrue(urls[1].startsWith("https://ghproxy.net/https://github.com/"))
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

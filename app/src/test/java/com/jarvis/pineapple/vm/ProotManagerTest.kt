package com.jarvis.pineapple.vm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PRoot 验收：ABI 拒绝、缺失二进制中文错误、创建失败原因、Kali 校验和。
 */
class ProotManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `requireArm64 refuses non arm64 abi`() {
        try {
            ProotManager.requireArm64("armeabi-v7a")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("armeabi-v7a"))
            assertTrue(e.message!!.contains("仅支持 arm64-v8a"))
        }
    }

    @Test
    fun `requireArm64 accepts arm64-v8a`() {
        ProotManager.requireArm64("arm64-v8a")
    }

    @Test
    fun `resolveSupportedAbi refuses x86_64 before download`() {
        val mgr = ProotManager(tmp.root, tmp.newFolder("cache"), { arrayOf("x86_64") })
        try {
            mgr.resolveSupportedAbi()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("x86_64"))
            assertTrue(e.message!!.contains("仅支持 arm64-v8a 的 PRoot 二进制"))
        }
    }

    @Test
    fun `executeCommand without binary returns chinese error and minus one`() = runBlocking {
        val mgr = ProotManager(tmp.root, tmp.newFolder("cache"), { arrayOf("arm64-v8a") })
        val result = mgr.executeCommand(
            ProotManager.ProotConfig(rootfsPath = tmp.root.absolutePath),
            "echo hi"
        )
        assertEquals(-1, result.exitCode)
        assertTrue(result.output.contains("PRoot 未安装"))
    }

    @Test
    fun `createFailureReason covers missing proot distro and empty rootfs`() {
        assertEquals("PRoot 未安装", HavenVmManager.createFailureReason(prootInstalled = false))
        assertEquals("未知发行版", HavenVmManager.createFailureReason(distroExists = false))
        assertEquals("发行版未下载", HavenVmManager.createFailureReason(distroDownloaded = false))
        assertEquals("校验失败", HavenVmManager.createFailureReason(distroDownloaded = false, downloadError = "校验失败"))
        assertEquals("rootfs 为空", HavenVmManager.createFailureReason(rootfsReady = false))
    }

    @Test
    fun `kali minimal rootfs has required sha256`() {
        assertEquals(64, KALI_MINIMAL_ARM64_SHA256.length)
        assertTrue(KALI_MINIMAL_ARM64_SHA256.matches(Regex("[0-9a-f]{64}")))
    }
}

package com.jarvis.pineapple.vm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
    fun `non executable binary is not installed`() {
        val filesDir = tmp.newFolder("non-executable-home")
        val mgr = ProotManager(filesDir, tmp.newFolder("cache"), { arrayOf("arm64-v8a") })
        val binary = File(filesDir, "proot/bin/proot").apply { writeText("binary") }

        assertFalse(binary.canExecute())
        assertFalse(mgr.isProotInstalled())
        assertFalse(mgr.isReady.value)

        assertTrue(binary.setExecutable(true, false))
        assertTrue(mgr.isProotInstalled())
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
    fun `session start requires proot and stop without process is idempotent`() = runBlocking {
        val rootfs = tmp.newFolder("rootfs")
        val mgr = ProotManager(tmp.newFolder("proot-home"), tmp.newFolder("cache"), { arrayOf("arm64-v8a") })
        val config = ProotManager.ProotConfig(rootfsPath = rootfs.absolutePath)

        assertFalse(mgr.startSession(config))
        assertTrue(mgr.stopSession(config))
    }

    @Test
    fun `session starts and stops a real process`() = runBlocking {
        val rootfs = tmp.newFolder("rootfs")
        val filesDir = tmp.newFolder("proot-home")
        val mgr = ProotManager(filesDir, tmp.newFolder("cache"), { arrayOf("arm64-v8a") })
        val executable = File(filesDir, "proot/bin/proot")
        executable.writeText(
            "#!/bin/sh\n" +
                "while [ \$# -gt 0 ]; do\n" +
                "  if [ \"\$1\" = \"-c\" ]; then shift; exec /bin/sh -c \"\$1\"; fi\n" +
                "  shift\n" +
                "done\n" +
                "exit 2\n"
        )
        assertTrue(executable.setExecutable(true))
        val config = ProotManager.ProotConfig(rootfsPath = rootfs.absolutePath)

        assertTrue(mgr.startSession(config))
        assertTrue(mgr.startSession(config))
        assertTrue(mgr.stopSession(config))
        assertTrue(mgr.stopSession(config))
    }

    @Test
    fun `guest copies reject paths outside rootfs`() = runBlocking {
        val rootfs = tmp.newFolder("rootfs")
        val outside = tmp.newFolder("outside")
        val secret = File(outside, "secret.txt").apply { writeText("private") }
        val source = File(tmp.root, "source.txt").apply { writeText("content") }
        val downloaded = File(tmp.root, "downloaded.txt")
        val mgr = ProotManager(tmp.newFolder("proot-home"), tmp.newFolder("cache"), { arrayOf("arm64-v8a") })
        val config = ProotManager.ProotConfig(rootfsPath = rootfs.absolutePath)

        assertFalse(mgr.copyToGuest(config, source.absolutePath, "../outside/copied.txt"))
        assertFalse(mgr.copyFromGuest(config, "../outside/secret.txt", downloaded.absolutePath))
        assertFalse(File(outside, "copied.txt").exists())
        assertFalse(downloaded.exists())
        assertEquals("private", secret.readText())
    }

    @Test
    fun `shell quote keeps package input inside one argument`() {
        assertEquals("'alpha'", ProotManager.shellQuote("alpha"))
        assertEquals("'a'\\''b'", ProotManager.shellQuote("a'b"))
    }

    @Test
    fun `shell detection rejects symlinks and lstat failures`() {
        val binary = tmp.newFile("bash")
        val regular = ProotManager(
            tmp.newFolder("regular-home"), tmp.newFolder("regular-cache"),
            { arrayOf("arm64-v8a") }, { false }
        )
        val symlink = ProotManager(
            tmp.newFolder("symlink-home"), tmp.newFolder("symlink-cache"),
            { arrayOf("arm64-v8a") }, { true }
        )
        val failed = ProotManager(
            tmp.newFolder("failed-home"), tmp.newFolder("failed-cache"),
            { arrayOf("arm64-v8a") }, { throw java.io.IOException("lstat failed") }
        )

        assertTrue(regular.isRealBinary(binary))
        assertFalse(symlink.isRealBinary(binary))
        assertFalse(failed.isRealBinary(binary))
    }

    @Test
    fun `package list preserves guest failure`() = runBlocking {
        val rootfs = tmp.newFolder("package-rootfs")
        val filesDir = tmp.newFolder("package-proot")
        val mgr = ProotManager(filesDir, tmp.newFolder("package-cache"), { arrayOf("arm64-v8a") })
        val executable = File(filesDir, "proot/bin/proot")
        executable.writeText(
            "#!/bin/sh\n" +
                "while [ \$# -gt 0 ]; do\n" +
                "  if [ \"\$1\" = \"-c\" ]; then printf 'query failed\\n'; exit 23; fi\n" +
                "  shift\n" +
                "done\n" +
                "exit 2\n"
        )
        assertTrue(executable.setExecutable(true))

        val result = mgr.listInstalledPackages(
            ProotManager.ProotConfig(rootfsPath = rootfs.absolutePath),
            "apt"
        )

        assertEquals(23, result.exitCode)
        assertTrue(result.output.contains("query failed"))
    }

    @Test
    fun `imported rootfs package manager requires a real executable path`() {
        val rootfs = tmp.newFolder("import-rootfs")
        assertEquals(null, HavenVmManager.detectPackageManager(rootfs))
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/apt-get").writeText("")
        assertEquals("apt", HavenVmManager.detectPackageManager(rootfs))
    }

    @Test
    fun `rootfs copy preserves symlinks executable files and isolation`() {
        val source = tmp.newFolder("copy-source")
        File(source, "bin").mkdirs()
        val executable = File(source, "bin/tool").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true, false)
        }
        java.nio.file.Files.createSymbolicLink(
            File(source, "sh").toPath(),
            java.nio.file.Paths.get("bin/tool")
        )
        val manager = ProotManager(
            tmp.newFolder("copy-home"),
            tmp.newFolder("copy-cache"),
            { arrayOf("arm64-v8a") },
            { file -> java.nio.file.Files.isSymbolicLink(file.toPath()) },
            { file -> java.nio.file.Files.readSymbolicLink(file.toPath()).toString() },
            { target, file ->
                java.nio.file.Files.createSymbolicLink(file.toPath(), java.nio.file.Paths.get(target))
            }
        )
        val target = File(tmp.root, "copy-target")

        manager.copyRootfs(source, target)
        File(target, "instance-only").writeText("isolated")

        assertTrue(java.nio.file.Files.isSymbolicLink(File(target, "sh").toPath()))
        assertEquals("bin/tool", java.nio.file.Files.readSymbolicLink(File(target, "sh").toPath()).toString())
        assertTrue(File(target, "bin/tool").canExecute())
        assertFalse(File(source, "instance-only").exists())
        assertEquals(executable.readText(), File(target, "bin/tool").readText())
    }

    @Test
    fun `kali minimal rootfs has required sha256`() {
        assertEquals(64, KALI_MINIMAL_ARM64_SHA256.length)
        assertTrue(KALI_MINIMAL_ARM64_SHA256.matches(Regex("[0-9a-f]{64}")))
    }
}

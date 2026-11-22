package com.jarvis.pineapple.vm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VmDistroCatalogTest {

    @Test
    fun catalogListsRequiredDistrosAndSizes() {
        val catalog = VmDistroManager.buildCatalog(emulationReady = false)
        assertEquals(15, catalog.size)
        val names = catalog.filter { it.architecture == "aarch64" }.map { "${it.name} ${it.version}" }
        assertTrue(names.any { it.startsWith("Debian 12") })
        assertTrue(names.any { it.startsWith("Alpine Linux 3.21") })
        assertTrue(names.any { it.startsWith("Debian 13") })
        assertTrue(names.any { it.contains("Ubuntu") && it.contains("24.04") })
        assertTrue(names.any { it.startsWith("Arch Linux") })
        assertTrue(names.any { it.startsWith("Void Linux") })
        assertTrue(names.any { it.startsWith("Kali Linux") })
        catalog.forEach { distro ->
            assertTrue("${distro.id} 缺少体积", distro.sizeBytes > 0)
        }
        val kali = catalog.first { it.id == "kali_nethunter_minimal" }
        assertEquals(KALI_MINIMAL_ARM64_SHA256, kali.checksumSha256)
        assertEquals(VmDistroManager.ROCKY_IMPORT, catalog.first { it.id == "rocky_linux" }.unavailableReason)
        assertEquals(VmDistroManager.WINDOWS_IMPORT, catalog.first { it.id == "windows" }.unavailableReason)
    }

    @Test
    fun aarch64DownloadsPointToPublishedAssets() {
        val catalog = VmDistroManager.buildCatalog(emulationReady = false)
        val arm = catalog.filter { it.architecture == "aarch64" }
        // 实机反馈 Debian 使用不存在的 v4.27.0 资产，会下载到 404 页面并显示 100%。
        arm.filter { it.id != "archlinux" && it.unavailableReason == null }.forEach { distro ->
            assertTrue("${distro.id} 不应指向不存在的 v4.27.0 Debian 资产", !distro.downloadUrl.contains("v4.27.0"))
        }
        val debian12 = arm.first { it.id == "debian_12" }
        assertTrue(debian12.downloadUrl.contains("v4.7.0/debian-bookworm-aarch64-pd-v4.7.0.tar.xz"))
        assertEquals(42_894_344L, debian12.sizeBytes)
        assertEquals(
            "4baa32280cc70b67e2c650777c1d974349f0cdf23afaabc305ad3bc6182b8df8",
            debian12.checksumSha256
        )
        val debian13 = arm.first { it.id == "debian_13" }
        assertTrue(debian13.downloadUrl.contains("v4.26.0/debian-trixie-aarch64-pd-v4.26.0.tar.xz"))
        assertEquals(
            "cda75346f2c9e09e8a802665745b5a7e2bd6d8584dbf1c86c8c57ef54c4e2d3c",
            debian13.checksumSha256
        )
        assertEquals(
            "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz",
            arm.first { it.id == "kali_nethunter_minimal" }.downloadUrl
        )
        // Arch 为滚动发布，依赖上游 MD5 sidecar；其余可下载的 arm64 发行版必须内置 SHA-256。
        arm.filter { it.id != "archlinux" && it.unavailableReason == null }.forEach { distro ->
            assertTrue("${distro.id} 缺少 SHA-256", distro.checksumSha256.isNotBlank())
        }
    }

    @Test
    fun distroDeletionMessageUsesActualResult() {
        val path = "app/src/main/java/com/jarvis/pineapple/ui/vm/VirtualMachineScreen.kt"
        val source = listOf(File(path), File("../$path")).firstOrNull { it.exists() }?.readText()
            ?: error("无法定位源文件: $path")
        val delete = source.substringAfter("onDelete = { id ->")
            .substringBefore("onCreateVm = { id ->")

        assertTrue(delete.contains("val deleted = distroManager.deleteDistro(id)"))
        assertTrue(delete.contains("if (deleted) \"已删除发行版\" else \"发行版删除失败\""))
    }

    @Test
    fun x86EntriesUnavailableWithoutEmulation() {
        val catalog = VmDistroManager.buildCatalog(emulationReady = false)
        val x86 = catalog.filter { it.emulatedX86 }
        assertEquals(6, x86.size)
        x86.forEach { distro ->
            assertEquals(VmDistroManager.X86_UNAVAILABLE, distro.unavailableReason)
            assertEquals("当前设备无法模拟 x86_64", distro.unavailableReason)
        }
        val ready = VmDistroManager.buildCatalog(emulationReady = true)
        ready.filter { it.emulatedX86 }.forEach { distro ->
            assertNull(distro.unavailableReason)
        }
    }
}

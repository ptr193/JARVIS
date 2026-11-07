package com.jarvis.pineapple.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VmDistroCatalogTest {

    @Test
    fun catalogListsRequiredDistrosAndSizes() {
        val catalog = VmDistroManager.buildCatalog(emulationReady = false)
        assertEquals(12, catalog.size)
        val names = catalog.filter { it.architecture == "aarch64" }.map { "${it.name} ${it.version}" }
        assertTrue(names.any { it.startsWith("Debian 12") })
        assertTrue(names.any { it.startsWith("Alpine Linux 3.21") })
        assertTrue(names.any { it.startsWith("Debian 13") })
        assertTrue(names.any { it.contains("Ubuntu") && it.contains("24.04") })
        assertTrue(names.any { it.startsWith("Arch Linux") })
        assertTrue(names.any { it.startsWith("Void Linux") })
        catalog.forEach { distro ->
            assertTrue("${distro.id} 缺少体积", distro.sizeBytes > 0)
        }
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

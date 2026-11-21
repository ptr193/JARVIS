package com.jarvis.pineapple.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VmTerminalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `cd resolves only existing directories inside rootfs`() {
        val root = tmp.newFolder("rootfs")
        File(root, "home/user").mkdirs()
        tmp.newFolder("outside")

        assertEquals(
            "/home/user",
            VmTerminal.resolveWorkingDirectory(root.absolutePath, "/root", "../home/user")
        )
        assertEquals("/", VmTerminal.resolveWorkingDirectory(root.absolutePath, "/root", "/"))
        assertNull(VmTerminal.resolveWorkingDirectory(root.absolutePath, "/root", "missing"))
        assertNull(VmTerminal.resolveWorkingDirectory(root.absolutePath, "/root", "../../outside"))
    }
}

package com.jarvis.pineapple.integration

import com.jarvis.pineapple.vm.VmFileManager
import com.jarvis.pineapple.vm.VmTerminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 集成测试：虚拟机 rootfs 文件操作与终端目录解析。
 */
class VmSubsystemIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `guest files and terminal paths use the same rootfs`() {
        val rootfs = tmp.newFolder("rootfs")
        val files = VmFileManager("vm_test", rootfs.absolutePath)

        assertTrue(files.createDirectory("workspace"))
        files.navigateTo("/workspace")
        assertEquals("/workspace", files.currentPath.value)
        assertTrue(files.getFileDetails("/workspace")?.isDirectory == true)
        assertTrue(files.createFile("task.txt", "original"))
        assertEquals("original", files.readFile("/workspace/task.txt"))
        assertTrue(files.writeFile("/workspace/task.txt", "updated"))
        assertEquals("updated", files.readFile("/workspace/task.txt"))
        assertTrue(files.rename("/workspace/task.txt", "done.txt"))
        assertEquals(1, files.searchFiles("done.txt", "/workspace").size)

        assertEquals(
            "/workspace",
            VmTerminal.resolveWorkingDirectory(rootfs.absolutePath, "/root", "../workspace")
        )
        assertEquals("/", VmTerminal.resolveWorkingDirectory(rootfs.absolutePath, "/workspace", "/"))
        assertNull(VmTerminal.resolveWorkingDirectory(rootfs.absolutePath, "/workspace", "../../outside"))
    }
}

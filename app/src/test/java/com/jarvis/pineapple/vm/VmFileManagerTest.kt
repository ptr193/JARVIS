package com.jarvis.pineapple.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VmFileManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `parent traversal cannot read write or delete outside rootfs`() {
        val rootfs = tmp.newFolder("rootfs")
        val outside = tmp.newFolder("outside")
        val secret = File(outside, "secret.txt").apply { writeText("private") }
        val manager = VmFileManager(rootfs.absolutePath)

        assertEquals("", manager.readFile("../outside/secret.txt"))
        assertFalse(manager.writeFile("../outside/secret.txt", "changed"))
        assertFalse(manager.createFile("../outside/new.txt", "new"))
        assertFalse(manager.delete("../outside"))
        assertEquals("private", secret.readText())
        assertFalse(File(outside, "new.txt").exists())
        assertTrue(outside.isDirectory)
    }

    @Test
    fun `prefix sibling and root deletion are rejected`() {
        val rootfs = tmp.newFolder("rootfs")
        val sibling = tmp.newFolder("rootfs-evil")
        File(sibling, "secret.txt").writeText("private")
        val manager = VmFileManager(rootfs.absolutePath)

        assertEquals("", manager.readFile("../rootfs-evil/secret.txt"))
        assertTrue(manager.listFiles("../rootfs-evil").isEmpty())
        assertEquals("/", manager.currentPath.value)
        assertFalse(manager.rename("../rootfs-evil/secret.txt", "renamed.txt"))
        assertFalse(manager.delete("/"))
        assertTrue(rootfs.isDirectory)
        assertTrue(File(sibling, "secret.txt").isFile)
    }

    @Test
    fun `rename cannot escape through destination name`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "source.txt").writeText("content")
        val manager = VmFileManager(rootfs.absolutePath)

        assertFalse(manager.rename("/source.txt", "../escaped.txt"))
        assertTrue(File(rootfs, "source.txt").isFile)
        assertFalse(File(tmp.root, "escaped.txt").exists())
    }
}

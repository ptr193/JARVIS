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

class VmSnapshotManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `snapshot metadata survives reload`() = runBlocking {
        val rootfs = rootfsWith("version", "one")
        val snapshotsDir = tmp.newFolder("snapshots")
        val manager = VmSnapshotManager("vm", rootfs.absolutePath, snapshotsDir)

        assertTrue(manager.createSnapshot("Initial", "first version") is SnapshotResult.Success)
        assertTrue(manager.renameSnapshot(manager.snapshots.value.single().id, "Base") is SnapshotResult.Success)
        assertTrue(
            manager.updateSnapshotDescription(manager.snapshots.value.single().id, "clean rootfs")
                is SnapshotResult.Success
        )

        val reloaded = VmSnapshotManager("vm", rootfs.absolutePath, snapshotsDir)
        val snapshot = reloaded.snapshots.value.single()
        assertEquals("Base", snapshot.name)
        assertEquals("clean rootfs", snapshot.description)
        assertTrue(snapshot.sizeBytes > 0)
    }

    @Test
    fun `rollback restores archived rootfs and current marker`() = runBlocking {
        val rootfs = rootfsWith("version", "one")
        val snapshotsDir = tmp.newFolder("snapshots")
        val manager = VmSnapshotManager("vm", rootfs.absolutePath, snapshotsDir)
        assertTrue(manager.createSnapshot("Initial") is SnapshotResult.Success)
        val id = manager.snapshots.value.single().id
        File(rootfs, "version").writeText("two")
        File(rootfs, "extra").writeText("remove me")

        val result = manager.rollbackToSnapshot(id)

        assertTrue(result is SnapshotResult.Success)
        assertEquals("one", File(rootfs, "version").readText())
        assertFalse(File(rootfs, "extra").exists())
        assertEquals(id, manager.getCurrentSnapshot()?.id)
        val reloaded = VmSnapshotManager("vm", rootfs.absolutePath, snapshotsDir)
        assertEquals(id, reloaded.getCurrentSnapshot()?.id)
    }

    @Test
    fun `corrupt snapshot leaves current rootfs untouched`() = runBlocking {
        val rootfs = rootfsWith("version", "current")
        val snapshotsDir = tmp.newFolder("snapshots")
        val manager = VmSnapshotManager("vm", rootfs.absolutePath, snapshotsDir)
        assertTrue(manager.createSnapshot("Initial") is SnapshotResult.Success)
        val snapshot = manager.snapshots.value.single()
        File(snapshotsDir, "${snapshot.id}.tar.gz").writeText("not a gzip archive")

        val result = manager.rollbackToSnapshot(snapshot.id)

        assertTrue(result is SnapshotResult.Failure)
        assertEquals("current", File(rootfs, "version").readText())
        assertFalse(manager.getSnapshots().single().isCurrent)
        assertFalse(rootfs.parentFile?.listFiles().orEmpty().any { it.name.contains(".restore-") })
    }

    @Test
    fun `missing ids and failed archive deletion report failure`() = runBlocking {
        val rootfs = rootfsWith("version", "one")
        val snapshotsDir = tmp.newFolder("snapshots")
        val manager = VmSnapshotManager("vm", rootfs.absolutePath, snapshotsDir)

        assertTrue(manager.renameSnapshot("missing", "name") is SnapshotResult.Failure)
        assertTrue(manager.updateSnapshotDescription("missing", "description") is SnapshotResult.Failure)
        assertTrue(manager.deleteSnapshot("missing") is SnapshotResult.Failure)

        assertTrue(manager.createSnapshot("Initial") is SnapshotResult.Success)
        val snapshot = manager.snapshots.value.single()
        val archive = File(snapshotsDir, "${snapshot.id}.tar.gz")
        assertTrue(archive.delete())
        assertTrue(archive.mkdir())
        File(archive, "child").writeText("prevents delete")

        assertTrue(manager.deleteSnapshot(snapshot.id) is SnapshotResult.Failure)
        assertEquals(1, manager.snapshots.value.size)
    }

    @Test
    fun `snapshot directory inside rootfs is rejected`() {
        val rootfs = rootfsWith("version", "one")
        try {
            VmSnapshotManager("vm", rootfs.absolutePath, File(rootfs, "snapshots"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("不能位于 rootfs"))
        }
    }

    private fun rootfsWith(name: String, content: String): File {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, name).writeText(content)
        return rootfs
    }
}

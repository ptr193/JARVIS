package com.jarvis.pineapple.vm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class HavenVmManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var proot: ProotManager
    private lateinit var distro: VmDistroManager
    private lateinit var runtime: FakeVmRuntime
    private lateinit var instances: File
    private lateinit var manager: HavenVmManager
    private lateinit var baseRootfs: File

    @Before
    fun setUp() {
        val files = tmp.newFolder("files")
        proot = ProotManager(files, tmp.newFolder("cache"), { arrayOf("arm64-v8a") }, { false })
        baseRootfs = proot.rootfsRoot().resolve("alpine_3_21")
        baseRootfs.resolve("bin").mkdirs()
        baseRootfs.resolve("bin/busybox").writeText("base")
        distro = VmDistroManager(tmp.newFolder("distro-cache"), proot)
        instances = File(files, "vm_instances")
        runtime = FakeVmRuntime()
        manager = newManager(runtime)
    }

    @Test
    fun `same distro instances have isolated persistent rootfs`() = runBlocking {
        val first = manager.createVm("first", "alpine_3_21").vm!!
        val second = manager.createVm("second", "alpine_3_21").vm!!

        assertNotEquals(first.id, second.id)
        assertEquals(UUID.fromString(first.id.removePrefix("vm_")).toString(), first.id.removePrefix("vm_"))
        assertNotEquals(first.rootfsPath, second.rootfsPath)
        assertFalse(first.rootfsPath == baseRootfs.absolutePath)
        File(first.rootfsPath, "instance.txt").writeText("first-only")
        assertFalse(File(second.rootfsPath, "instance.txt").exists())
        assertFalse(File(baseRootfs, "instance.txt").exists())
        assertEquals("base", File(second.rootfsPath, "bin/busybox").readText())

        assertTrue(manager.startVm(first.id))
        val reloaded = newManager(FakeVmRuntime())
        assertEquals(2, reloaded.vms.value.size)
        assertEquals(HavenVmManager.HavenVmState.STOPPED, reloaded.findVm(first.id)?.state)
        assertEquals(listOf("base-files", "busybox"), reloaded.getInstalledPackages(first.id))
        assertTrue(File(reloaded.findVm(first.id)!!.rootfsPath, "instance.txt").isFile)
    }

    @Test
    fun `state transitions are legal and idempotent`() = runBlocking {
        val vm = manager.createVm("states", "alpine_3_21").vm!!
        assertEquals(-1, manager.executeCommandResult(vm.id, "echo no")?.exitCode)

        assertTrue(manager.startVm(vm.id))
        assertTrue(manager.startVm(vm.id))
        assertEquals(1, runtime.startCalls.get())
        assertTrue(manager.pauseVm(vm.id))
        assertFalse(manager.startVm(vm.id))
        assertEquals(HavenVmManager.HavenVmState.PAUSED, manager.findVm(vm.id)?.state)
        assertTrue(manager.resumeVm(vm.id))
        assertTrue(manager.stopVm(vm.id))
        assertTrue(manager.stopVm(vm.id))
        assertEquals(1, runtime.stopCalls.get())
        assertFalse(manager.resumeVm(vm.id))
    }

    @Test
    fun `failed stop restores paused state and failed copy leaves no registration`() = runBlocking {
        val vm = manager.createVm("stop-failure", "alpine_3_21").vm!!
        assertTrue(manager.startVm(vm.id))
        assertTrue(manager.pauseVm(vm.id))
        runtime.stopResult = false

        assertFalse(manager.stopVm(vm.id))
        assertEquals(HavenVmManager.HavenVmState.PAUSED, manager.findVm(vm.id)?.state)
        assertTrue(manager.lastError.value.orEmpty().contains("停止失败"))

        runtime.stopResult = true
        assertTrue(manager.stopVm(vm.id))
        runtime.copyFailure = IOException("injected copy failure")
        val result = manager.createVm("copy-failure", "alpine_3_21")
        assertFalse(result.isSuccess)
        assertTrue(result.error.orEmpty().contains("injected copy failure"))
        assertEquals(1, manager.vms.value.size)
        assertEquals(1, instances.listFiles().orEmpty().count { File(it, "vm.properties").isFile })
    }

    @Test
    fun `commands serialize per vm while different vms can run concurrently`() = runBlocking {
        val first = manager.createVm("first", "alpine_3_21").vm!!
        val second = manager.createVm("second", "alpine_3_21").vm!!
        assertTrue(manager.startVm(first.id))
        assertTrue(manager.startVm(second.id))
        runtime.resetConcurrencySamples()

        val jobs = listOf(
            async { manager.executeCommandResult(first.id, "work-one") },
            async { manager.executeCommandResult(first.id, "work-two") },
            async { manager.executeCommandResult(second.id, "work-three") }
        )
        val results = jobs.awaitAll()

        assertTrue(results.all { it?.exitCode == 0 })
        assertEquals(1, runtime.maxConcurrentFor(first.rootfsPath))
        assertEquals(1, runtime.maxConcurrentFor(second.rootfsPath))
        assertTrue("different VMs should overlap", runtime.maxGlobalConcurrency.get() >= 2)
    }

    @Test
    fun `delete stops desktop releases snapshot and rootfs and failed delete retains registration`() = runBlocking {
        val vm = manager.createVm("delete", "alpine_3_21").vm!!
        val instanceDir = File(vm.rootfsPath).parentFile!!
        assertTrue(manager.startVm(vm.id))
        runtime.stopResult = false

        assertFalse(manager.deleteVm(vm.id))
        assertNotNull(manager.findVm(vm.id))
        assertTrue(File(vm.rootfsPath).isDirectory)

        runtime.stopResult = true
        assertTrue(manager.stopVm(vm.id))
        val snapshots = snapshotManager(manager, vm.id)
        assertTrue(snapshots.createSnapshot("before-delete") is SnapshotResult.Success)
        assertTrue(manager.startVm(vm.id))

        assertTrue(manager.deleteVm(vm.id))
        assertNull(manager.findVm(vm.id))
        assertFalse(File(vm.rootfsPath).exists())
        assertFalse(manager.snapshotsDir(vm.id).exists())
        assertTrue(runtime.stoppedBackgrounds.contains("desktop-${vm.id}"))
        assertTrue(runtime.stoppedBackgrounds.contains("desktop-${vm.id}-browser"))
        assertFalse(instanceDir.exists())
    }

    @Test
    fun `corrupt registry is visible and is not treated as empty valid state`() = runBlocking {
        val vm = manager.createVm("registry", "alpine_3_21").vm!!
        File(File(vm.rootfsPath).parentFile, "vm.properties").writeText("id=\\uZZZZ\n")

        val reloaded = newManager(FakeVmRuntime())

        assertTrue(reloaded.vms.value.isEmpty())
        assertTrue(reloaded.lastError.value.orEmpty().contains("部分虚拟机登记未恢复"))
    }

    @Test
    fun `snapshot creation and rollback reject running vm without touching rootfs`() = runBlocking {
        val vm = manager.createVm("snapshots", "alpine_3_21").vm!!
        assertTrue(manager.startVm(vm.id))
        File(vm.rootfsPath, "version").writeText("current")
        val snapshots = snapshotManager(manager, vm.id)

        assertTrue(snapshots.createSnapshot("running") is SnapshotResult.Failure)
        assertTrue(snapshots.rollbackToSnapshot("missing") is SnapshotResult.Failure)
        assertEquals("current", File(vm.rootfsPath, "version").readText())
        assertTrue(snapshots.snapshots.value.isEmpty())
    }

    private fun newManager(vmRuntime: VmRuntime): HavenVmManager = HavenVmManager(
        prootManager = proot,
        distroManager = distro,
        runtime = vmRuntime,
        instancesDir = instances
    )

    private fun snapshotManager(vmManager: HavenVmManager, vmId: String): VmSnapshotManager {
        val vm = vmManager.findVm(vmId)!!
        return VmSnapshotManager(
            vmId = vmId,
            rootfsPath = vm.rootfsPath,
            snapshotsDir = vmManager.snapshotsDir(vmId),
            stateProvider = { vmManager.findVm(vmId)?.state },
            operationLock = vmManager.operationLockFor(vmId),
            prepareStopped = { vmManager.prepareStoppedVm(vmId) }
        )
    }

    private class FakeVmRuntime : VmRuntime {
        var startResult = true
        var stopResult = true
        var pauseResult = true
        var resumeResult = true
        var copyFailure: IOException? = null
        val startCalls = AtomicInteger()
        val stopCalls = AtomicInteger()
        val stoppedBackgrounds = mutableListOf<String>()
        val maxGlobalConcurrency = AtomicInteger()
        private val globalConcurrency = AtomicInteger()
        private val perRootConcurrency = ConcurrentHashMap<String, AtomicInteger>()
        private val perRootMaximum = ConcurrentHashMap<String, AtomicInteger>()

        override fun isProotInstalled(): Boolean = true

        override suspend fun executeCommand(
            config: ProotManager.ProotConfig,
            command: String
        ): ProotManager.CommandResult {
            val global = globalConcurrency.incrementAndGet()
            maxGlobalConcurrency.updateAndGet { maxOf(it, global) }
            val local = perRootConcurrency.computeIfAbsent(config.rootfsPath) { AtomicInteger() }
            val current = local.incrementAndGet()
            perRootMaximum.computeIfAbsent(config.rootfsPath) { AtomicInteger() }
                .updateAndGet { maxOf(it, current) }
            return try {
                if (command.startsWith("work")) delay(60)
                if (command == "echo JARVIS_VM_OK") {
                    ProotManager.CommandResult(command, "JARVIS_VM_OK", 0, 0)
                } else {
                    ProotManager.CommandResult(command, "ok:$command", 0, 1)
                }
            } finally {
                local.decrementAndGet()
                globalConcurrency.decrementAndGet()
            }
        }

        override suspend fun startSession(config: ProotManager.ProotConfig): Boolean {
            startCalls.incrementAndGet()
            return startResult
        }

        override suspend fun stopSession(config: ProotManager.ProotConfig): Boolean {
            stopCalls.incrementAndGet()
            return stopResult
        }

        override suspend fun pauseSession(config: ProotManager.ProotConfig): Boolean = pauseResult
        override suspend fun resumeSession(config: ProotManager.ProotConfig): Boolean = resumeResult
        override suspend fun installPackage(
            config: ProotManager.ProotConfig,
            packageManager: String,
            packageName: String
        ): Boolean = true
        override suspend fun uninstallPackage(
            config: ProotManager.ProotConfig,
            packageManager: String,
            packageName: String
        ): Boolean = true
        override suspend fun updatePackages(config: ProotManager.ProotConfig, packageManager: String): Boolean = true

        override suspend fun listInstalledPackages(
            config: ProotManager.ProotConfig,
            packageManager: String
        ): ProotManager.CommandResult = ProotManager.CommandResult("list", "base-files\nbusybox", 0, 0)

        override suspend fun stopBackground(key: String): Boolean {
            stoppedBackgrounds += key
            return true
        }

        override suspend fun launchBackground(
            config: ProotManager.ProotConfig,
            key: String,
            command: String
        ): Boolean = true

        override fun copyRootfs(source: File, target: File) {
            copyFailure?.let { throw it }
            if (!source.copyRecursively(target, overwrite = false)) throw IOException("copyRecursively failed")
        }

        fun maxConcurrentFor(rootfs: String): Int = perRootMaximum[rootfs]?.get() ?: 0

        fun resetConcurrencySamples() {
            maxGlobalConcurrency.set(0)
            perRootMaximum.clear()
        }
    }
}

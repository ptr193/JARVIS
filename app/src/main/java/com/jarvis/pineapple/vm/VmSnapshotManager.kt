package com.jarvis.pineapple.vm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
import java.util.UUID

/** 虚拟机磁盘快照信息。 */
data class VmSnapshot(
    val id: String,
    val name: String,
    val description: String = "",
    val vmId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0,
    val isCurrent: Boolean = false
)

sealed class SnapshotResult {
    data class Success(val message: String) : SnapshotResult()
    data class Failure(val error: String) : SnapshotResult()
}

/**
 * 通过真实 .tar.gz 归档创建、回滚和删除快照。
 * 创建与回滚仅在 STOPPED 状态且持有对应 VM 锁时执行。
 */
class VmSnapshotManager(
    private val vmId: String,
    private val rootfsPath: String,
    private val snapshotsDir: File,
    private val stateProvider: () -> HavenVmManager.HavenVmState? = { HavenVmManager.HavenVmState.STOPPED },
    private val operationLock: Mutex = Mutex(),
    private val prepareStopped: suspend () -> Boolean = { true }
) {
    private val _snapshots = MutableStateFlow<List<VmSnapshot>>(emptyList())
    val snapshots: StateFlow<List<VmSnapshot>> = _snapshots.asStateFlow()

    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val operationGuard = Any()
    private val currentFile: File get() = File(snapshotsDir, ".current")

    init {
        if (!snapshotsDir.exists() && !snapshotsDir.mkdirs()) {
            throw IllegalArgumentException("无法创建快照目录: ${snapshotsDir.absolutePath}")
        }
        val root = File(rootfsPath).canonicalFile
        val snapshots = snapshotsDir.canonicalFile
        if (snapshots == root || snapshots.path.startsWith(root.path + File.separator)) {
            throw IllegalArgumentException("快照目录不能位于 rootfs 内")
        }
        reload()
    }

    private fun snapshotFile(id: String): File = File(snapshotsDir, "$id.tar.gz")
    private fun metadataFile(id: String): File = File(snapshotsDir, "$id.properties")

    /** 元数据或当前标记损坏时显式报错，不静默降级为空名称或空列表。 */
    fun reload() {
        synchronized(operationGuard) {
            if (_isOperating.value) {
                _loadError.value = "快照操作进行中，暂不能重新载入"
                return
            }
        }
        try {
            val currentId = if (currentFile.isFile) currentFile.readText().trim() else null
            val files = snapshotsDir.listFiles { file -> file.isFile && file.name.endsWith(".tar.gz") }
                ?: throw IOException("无法读取快照目录")
            val existing = files.map { file ->
                val id = file.name.removeSuffix(".tar.gz")
                val properties = readMetadata(id)
                VmSnapshot(
                    id = id,
                    name = properties.getProperty("name")?.takeIf { it.isNotBlank() }
                        ?: throw IOException("快照 $id 元数据缺少名称"),
                    description = properties.getProperty("description", ""),
                    vmId = vmId,
                    createdAt = properties.getProperty("createdAt")?.toLongOrNull()
                        ?: throw IOException("快照 $id 元数据缺少有效时间"),
                    sizeBytes = file.length(),
                    isCurrent = id == currentId
                )
            }.sortedByDescending { it.createdAt }
            _snapshots.value = existing
            _loadError.value = null
        } catch (e: Exception) {
            _snapshots.value = emptyList()
            _loadError.value = "快照元数据读取失败: ${e.message ?: e.toString()}"
        }
    }

    suspend fun createSnapshot(name: String, description: String = ""): SnapshotResult =
        withContext(Dispatchers.IO) {
            operationLock.withLock {
                if (name.isBlank()) return@withLock SnapshotResult.Failure("快照名称不能为空")
                if (stateProvider() != HavenVmManager.HavenVmState.STOPPED) {
                    return@withLock SnapshotResult.Failure("创建快照前必须停止虚拟机")
                }
                if (!beginOperation()) return@withLock SnapshotResult.Failure("已有快照操作正在进行")
                val id = "snap_${System.currentTimeMillis()}_${UUID.randomUUID()}"
                val temporaryArchive = File(snapshotsDir, "$id.tmp")
                val outFile = snapshotFile(id)
                try {
                    if (!prepareStopped()) return@withLock SnapshotResult.Failure("无法停止残留会话，禁止创建快照")
                    if (stateProvider() != HavenVmManager.HavenVmState.STOPPED) {
                        return@withLock SnapshotResult.Failure("虚拟机状态已变化，取消创建快照")
                    }
                    val root = File(rootfsPath)
                    if (!root.isDirectory) return@withLock SnapshotResult.Failure("rootfs 不存在")

                    TarExtractor.createArchive(root, temporaryArchive)
                    if (!temporaryArchive.renameTo(outFile)) throw IOException("无法保存快照归档")
                    val snapshot = VmSnapshot(
                        id = id,
                        name = name.trim(),
                        description = description,
                        vmId = vmId,
                        sizeBytes = outFile.length()
                    )
                    writeMetadata(snapshot)
                    _snapshots.value = (listOf(snapshot) + _snapshots.value).sortedByDescending { it.createdAt }
                    _loadError.value = null
                    SnapshotResult.Success("快照 '${snapshot.name}' 创建成功")
                } catch (e: Exception) {
                    if (outFile.exists() && !outFile.delete()) {
                        _loadError.value = "快照创建失败且归档清理失败: ${outFile.absolutePath}"
                    }
                    metadataFile(id).delete()
                    SnapshotResult.Failure("创建快照失败: ${e.message ?: e.toString()}")
                } finally {
                    temporaryArchive.delete()
                    endOperation()
                }
            }
        }

    /** 先完整解压再交换目录；任何替换失败均尽力恢复原 rootfs。 */
    suspend fun rollbackToSnapshot(snapshotId: String): SnapshotResult = withContext(Dispatchers.IO) {
        operationLock.withLock {
            if (stateProvider() != HavenVmManager.HavenVmState.STOPPED) {
                return@withLock SnapshotResult.Failure("回滚快照前必须停止虚拟机")
            }
            if (!beginOperation()) return@withLock SnapshotResult.Failure("已有快照操作正在进行")
            val snapshot = _snapshots.value.firstOrNull { it.id == snapshotId }
            if (snapshot == null) {
                endOperation()
                return@withLock SnapshotResult.Failure("快照不存在: $snapshotId")
            }
            val suffix = UUID.randomUUID().toString()
            val root = File(rootfsPath).canonicalFile
            val parent = root.parentFile
            val staging = parent?.let { File(it, ".${root.name}.restore-$suffix") }
            val backup = parent?.let { File(it, ".${root.name}.backup-$suffix") }
            try {
                if (!prepareStopped()) return@withLock SnapshotResult.Failure("无法停止残留会话，禁止回滚快照")
                if (stateProvider() != HavenVmManager.HavenVmState.STOPPED) {
                    return@withLock SnapshotResult.Failure("虚拟机状态已变化，取消回滚快照")
                }
                val file = snapshotFile(snapshot.id)
                if (!file.isFile) return@withLock SnapshotResult.Failure("快照归档不存在: $snapshotId")
                if (!root.isDirectory || staging == null || backup == null) {
                    return@withLock SnapshotResult.Failure("rootfs 不存在")
                }

                TarExtractor.extract(file, staging)
                if (!root.renameTo(backup)) throw IOException("无法备份当前 rootfs")
                if (!staging.renameTo(root)) {
                    val restored = backup.renameTo(root)
                    throw IOException(
                        if (restored) "无法替换 rootfs，已恢复原目录"
                        else "无法替换 rootfs，原目录备份位于 ${backup.absolutePath}"
                    )
                }

                try {
                    writeCurrentMarker(snapshot.id)
                } catch (e: Exception) {
                    val error = "rootfs 已回滚到 '${snapshot.name}'，但当前快照标记保存失败: ${e.message}"
                    _loadError.value = error
                    return@withLock SnapshotResult.Failure(error)
                }
                _snapshots.value = _snapshots.value.map { it.copy(isCurrent = it.id == snapshotId) }
                if (!backup.deleteRecursively()) {
                    val error = "rootfs 已回滚到 '${snapshot.name}'，但旧目录清理失败: ${backup.absolutePath}"
                    _loadError.value = error
                    return@withLock SnapshotResult.Failure(error)
                }
                _loadError.value = null
                SnapshotResult.Success("已回滚到快照 '${snapshot.name}'")
            } catch (e: Exception) {
                staging?.deleteRecursively()
                if (!root.exists() && backup?.exists() == true && !backup.renameTo(root)) {
                    return@withLock SnapshotResult.Failure(
                        "回滚失败且原目录恢复失败，备份位于 ${backup.absolutePath}: ${e.message}"
                    )
                }
                SnapshotResult.Failure("回滚失败: ${e.message ?: e.toString()}")
            } finally {
                staging?.deleteRecursively()
                endOperation()
            }
        }
    }

    fun deleteSnapshot(snapshotId: String): SnapshotResult {
        if (!beginOperation()) return SnapshotResult.Failure("已有快照操作正在进行")
        try {
            val snapshot = _snapshots.value.firstOrNull { it.id == snapshotId }
                ?: return SnapshotResult.Failure("快照不存在: $snapshotId")
            val archive = snapshotFile(snapshot.id)
            if (!archive.isFile || !archive.delete()) {
                return SnapshotResult.Failure("快照归档删除失败: ${snapshot.name}")
            }
            // 主归档已经消失，内存列表必须同步，后续清理失败单独报告。
            _snapshots.value = _snapshots.value.filterNot { it.id == snapshotId }
            val failures = mutableListOf<String>()
            val metadata = metadataFile(snapshot.id)
            if (metadata.exists() && !metadata.delete()) failures += "元数据未删除"
            if (snapshot.isCurrent && currentFile.exists() && !currentFile.delete()) failures += "当前标记未删除"
            return if (failures.isEmpty()) {
                SnapshotResult.Success("快照 '${snapshot.name}' 已删除")
            } else {
                val error = "快照归档已删除，但${failures.joinToString("、")}"
                _loadError.value = error
                SnapshotResult.Failure(error)
            }
        } catch (e: Exception) {
            return SnapshotResult.Failure("删除快照失败: ${e.message ?: e.toString()}")
        } finally {
            endOperation()
        }
    }

    fun renameSnapshot(snapshotId: String, newName: String): SnapshotResult {
        if (newName.isBlank()) return SnapshotResult.Failure("快照名称不能为空")
        val snapshot = _snapshots.value.firstOrNull { it.id == snapshotId }
            ?: return SnapshotResult.Failure("快照不存在: $snapshotId")
        return updateMetadata(snapshot.copy(name = newName.trim()), "快照已重命名为 '${newName.trim()}'")
    }

    fun updateSnapshotDescription(snapshotId: String, description: String): SnapshotResult {
        val snapshot = _snapshots.value.firstOrNull { it.id == snapshotId }
            ?: return SnapshotResult.Failure("快照不存在: $snapshotId")
        return updateMetadata(snapshot.copy(description = description), "快照描述已更新")
    }

    private fun updateMetadata(snapshot: VmSnapshot, successMessage: String): SnapshotResult {
        if (!beginOperation()) return SnapshotResult.Failure("已有快照操作正在进行")
        return try {
            writeMetadata(snapshot)
            _snapshots.value = _snapshots.value.map { if (it.id == snapshot.id) snapshot else it }
            SnapshotResult.Success(successMessage)
        } catch (e: Exception) {
            SnapshotResult.Failure("保存快照信息失败: ${e.message ?: e.toString()}")
        } finally {
            endOperation()
        }
    }

    private fun writeMetadata(snapshot: VmSnapshot) {
        val target = metadataFile(snapshot.id)
        val temporary = File(snapshotsDir, "${snapshot.id}.properties.tmp")
        try {
            val properties = Properties()
            properties.setProperty("name", snapshot.name)
            properties.setProperty("description", snapshot.description)
            properties.setProperty("createdAt", snapshot.createdAt.toString())
            FileOutputStream(temporary).use { properties.store(it, null) }
            if (!temporary.renameTo(target)) throw IOException("无法原子保存快照元数据")
        } finally {
            temporary.delete()
        }
    }

    private fun writeCurrentMarker(snapshotId: String) {
        val temporary = File(snapshotsDir, ".current.tmp")
        try {
            temporary.writeText(snapshotId)
            if (!temporary.renameTo(currentFile)) throw IOException("无法原子保存当前快照标记")
        } finally {
            temporary.delete()
        }
    }

    private fun readMetadata(snapshotId: String): Properties {
        val properties = Properties()
        val file = metadataFile(snapshotId)
        if (!file.isFile) throw IOException("快照 $snapshotId 元数据缺失")
        file.inputStream().use { properties.load(it) }
        return properties
    }

    private fun beginOperation(): Boolean = synchronized(operationGuard) {
        if (_isOperating.value) false else {
            _isOperating.value = true
            true
        }
    }

    private fun endOperation() {
        synchronized(operationGuard) { _isOperating.value = false }
    }

    fun getCurrentSnapshot(): VmSnapshot? = _snapshots.value.firstOrNull { it.isCurrent }
    fun getSnapshots(): List<VmSnapshot> = _snapshots.value.sortedByDescending { it.createdAt }

    fun compareSnapshots(snapshotId1: String, snapshotId2: String): String {
        val snap1 = _snapshots.value.firstOrNull { it.id == snapshotId1 }
        val snap2 = _snapshots.value.firstOrNull { it.id == snapshotId2 }
        return if (snap1 != null && snap2 != null) {
            """
            快照比较: '${snap1.name}' vs '${snap2.name}'
            ----------------------------------------
            快照时间: ${formatTime(snap1.createdAt)} vs ${formatTime(snap2.createdAt)}
            快照大小: ${formatSize(snap1.sizeBytes)} vs ${formatSize(snap2.sizeBytes)}
            快照时间差: ${kotlin.math.abs(snap1.createdAt - snap2.createdAt) / 1000} 秒
            """.trimIndent()
        } else {
            "无法比较：快照不存在"
        }
    }

    fun cleanupOldSnapshots(keepCount: Int = 5): SnapshotResult {
        if (keepCount < 0) return SnapshotResult.Failure("保留数量不能为负数")
        val sorted = _snapshots.value.sortedByDescending { it.createdAt }
        if (sorted.size <= keepCount) return SnapshotResult.Success("快照数量未超过限制，无需清理")
        var deleted = 0
        val failures = mutableListOf<String>()
        sorted.drop(keepCount).forEach { snapshot ->
            when (val result = deleteSnapshot(snapshot.id)) {
                is SnapshotResult.Success -> deleted++
                is SnapshotResult.Failure -> failures.add(result.error)
            }
        }
        return if (failures.isEmpty()) {
            SnapshotResult.Success("已清理 $deleted 个旧快照")
        } else {
            SnapshotResult.Failure("已清理 $deleted 个旧快照，${failures.size} 个删除失败: ${failures.joinToString()}")
        }
    }

    private fun formatTime(timestamp: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

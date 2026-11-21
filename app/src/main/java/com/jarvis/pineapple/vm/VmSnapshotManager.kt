package com.jarvis.pineapple.vm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import java.util.UUID

/**
 * 快照信息
 */
data class VmSnapshot(
    val id: String,
    val name: String,
    val description: String = "",
    val vmId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0,
    val isCurrent: Boolean = false
)

/**
 * 快照操作结果
 */
sealed class SnapshotResult {
    data class Success(val message: String) : SnapshotResult()
    data class Failure(val error: String) : SnapshotResult()
}

/**
 * 虚拟机快照管理器（REQ-01-S3）。
 *
 * 通过对 rootfs 打包为 .tar.gz 生成真实快照，并支持回滚、删除、列出。
 */
class VmSnapshotManager(
    private val vmId: String,
    private val rootfsPath: String,
    private val snapshotsDir: File
) {
    private val _snapshots = MutableStateFlow<List<VmSnapshot>>(emptyList())
    val snapshots: StateFlow<List<VmSnapshot>> = _snapshots.asStateFlow()

    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    private val currentFile: File
        get() = File(snapshotsDir, ".current")

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

    /** 从磁盘重新载入快照列表及持久化的名称、备注。 */
    fun reload() {
        val currentId = runCatching { currentFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        val existing = snapshotsDir.listFiles { file -> file.isFile && file.name.endsWith(".tar.gz") }
            ?.map { file ->
                val id = file.name.removeSuffix(".tar.gz")
                val properties = readMetadata(id)
                VmSnapshot(
                    id = id,
                    name = properties.getProperty("name", id),
                    description = properties.getProperty("description", ""),
                    vmId = vmId,
                    createdAt = properties.getProperty("createdAt")?.toLongOrNull() ?: file.lastModified(),
                    sizeBytes = file.length(),
                    isCurrent = id == currentId
                )
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
        _snapshots.value = existing
    }

    /** 创建快照（真实打包 rootfs）。 */
    suspend fun createSnapshot(name: String, description: String = ""): SnapshotResult = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext SnapshotResult.Failure("快照名称不能为空")
        if (_isOperating.value) return@withContext SnapshotResult.Failure("已有快照操作正在进行")
        _isOperating.value = true
        val id = "snap_${System.currentTimeMillis()}_${UUID.randomUUID()}"
        val temporaryArchive = File(snapshotsDir, "$id.tmp")
        val outFile = snapshotFile(id)
        try {
            val root = File(rootfsPath)
            if (!root.isDirectory) return@withContext SnapshotResult.Failure("rootfs 不存在")

            TarExtractor.createArchive(root, temporaryArchive)
            if (!temporaryArchive.renameTo(outFile)) {
                throw IllegalArgumentException("无法保存快照归档")
            }
            val snapshot = VmSnapshot(
                id = id,
                name = name.trim(),
                description = description,
                vmId = vmId,
                sizeBytes = outFile.length()
            )
            writeMetadata(snapshot)
            _snapshots.value = listOf(snapshot) + _snapshots.value
            SnapshotResult.Success("快照 '${snapshot.name}' 创建成功")
        } catch (e: Exception) {
            outFile.delete()
            metadataFile(id).delete()
            SnapshotResult.Failure("创建快照失败: ${e.message}")
        } finally {
            temporaryArchive.delete()
            _isOperating.value = false
        }
    }

    /**
     * 回滚到指定快照。先完整解压到同文件系统的暂存目录，再交换目录；
     * 解压失败时不会删除或修改当前 rootfs。
     */
    suspend fun rollbackToSnapshot(snapshotId: String): SnapshotResult = withContext(Dispatchers.IO) {
        if (_isOperating.value) return@withContext SnapshotResult.Failure("已有快照操作正在进行")
        val snapshot = _snapshots.value.firstOrNull { it.id == snapshotId }
            ?: return@withContext SnapshotResult.Failure("快照不存在: $snapshotId")
        _isOperating.value = true
        val suffix = UUID.randomUUID().toString()
        val root = File(rootfsPath).canonicalFile
        val parent = root.parentFile
        val staging = parent?.let { File(it, ".${root.name}.restore-$suffix") }
        val backup = parent?.let { File(it, ".${root.name}.backup-$suffix") }
        try {
            val file = snapshotFile(snapshot.id)
            if (!file.isFile) return@withContext SnapshotResult.Failure("快照归档不存在: $snapshotId")
            if (!root.isDirectory || staging == null || backup == null) {
                return@withContext SnapshotResult.Failure("rootfs 不存在")
            }

            TarExtractor.extract(file, staging)
            if (!root.renameTo(backup)) {
                throw IllegalArgumentException("无法备份当前 rootfs")
            }
            if (!staging.renameTo(root)) {
                val restored = backup.renameTo(root)
                throw IllegalArgumentException(
                    if (restored) "无法替换 rootfs，已恢复原目录" else "无法替换 rootfs，原目录备份位于 ${backup.absolutePath}"
                )
            }

            val metadataWarning = try {
                currentFile.writeText(snapshot.id)
                null
            } catch (e: Exception) {
                e.message
            }
            _snapshots.value = _snapshots.value.map { it.copy(isCurrent = it.id == snapshotId) }
            backup.deleteRecursively()
            SnapshotResult.Success(
                if (metadataWarning == null) "已回滚到快照 '${snapshot.name}'"
                else "已回滚到快照 '${snapshot.name}'，但当前快照标记保存失败: $metadataWarning"
            )
        } catch (e: Exception) {
            staging?.deleteRecursively()
            if (!root.exists() && backup?.exists() == true) backup.renameTo(root)
            SnapshotResult.Failure("回滚失败: ${e.message}")
        } finally {
            staging?.deleteRecursively()
            _isOperating.value = false
        }
    }

    fun deleteSnapshot(snapshotId: String): SnapshotResult {
        val snapshot = _snapshots.value.firstOrNull { it.id == snapshotId }
            ?: return SnapshotResult.Failure("快照不存在: $snapshotId")
        return try {
            val archive = snapshotFile(snapshot.id)
            if (!archive.isFile || !archive.delete()) {
                return SnapshotResult.Failure("快照归档删除失败: ${snapshot.name}")
            }
            metadataFile(snapshot.id).delete()
            if (snapshot.isCurrent) currentFile.delete()
            _snapshots.value = _snapshots.value.filterNot { it.id == snapshotId }
            SnapshotResult.Success("快照 '${snapshot.name}' 已删除")
        } catch (e: Exception) {
            SnapshotResult.Failure("删除快照失败: ${e.message}")
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
        return try {
            writeMetadata(snapshot)
            _snapshots.value = _snapshots.value.map { if (it.id == snapshot.id) snapshot else it }
            SnapshotResult.Success(successMessage)
        } catch (e: Exception) {
            SnapshotResult.Failure("保存快照信息失败: ${e.message}")
        }
    }

    private fun writeMetadata(snapshot: VmSnapshot) {
        val properties = Properties()
        properties.setProperty("name", snapshot.name)
        properties.setProperty("description", snapshot.description)
        properties.setProperty("createdAt", snapshot.createdAt.toString())
        metadataFile(snapshot.id).outputStream().use { properties.store(it, null) }
    }

    private fun readMetadata(snapshotId: String): Properties {
        val properties = Properties()
        val file = metadataFile(snapshotId)
        if (file.isFile) {
            runCatching { file.inputStream().use { properties.load(it) } }
        }
        return properties
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
        if (sorted.size <= keepCount) {
            return SnapshotResult.Success("快照数量未超过限制，无需清理")
        }
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

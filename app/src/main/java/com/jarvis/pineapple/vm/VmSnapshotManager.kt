package com.jarvis.pineapple.vm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

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

    init {
        if (!snapshotsDir.exists()) snapshotsDir.mkdirs()
        reload()
    }

    private fun snapshotFile(id: String): File = File(snapshotsDir, "$id.tar.gz")

    /** 从磁盘重新载入快照列表。 */
    fun reload() {
        val existing = snapshotsDir.listFiles { f -> f.name.endsWith(".tar.gz") }
            ?.map { file ->
                VmSnapshot(
                    id = file.name.removeSuffix(".tar.gz"),
                    name = file.name.removeSuffix(".tar.gz"),
                    vmId = vmId,
                    createdAt = file.lastModified(),
                    sizeBytes = file.length()
                )
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
        _snapshots.value = existing
    }

    /**
     * 创建快照（真实打包 rootfs）。
     */
    suspend fun createSnapshot(name: String, description: String = ""): SnapshotResult = withContext(Dispatchers.IO) {
        _isOperating.value = true
        try {
            val root = File(rootfsPath)
            if (!root.exists()) return@withContext SnapshotResult.Failure("rootfs 不存在")

            val id = "snap_${System.currentTimeMillis()}"
            val outFile = snapshotFile(id)
            TarExtractor.createArchive(root, outFile)

            val snapshot = VmSnapshot(
                id = id,
                name = name,
                description = description,
                vmId = vmId,
                sizeBytes = outFile.length()
            )
            _snapshots.value = listOf(snapshot) + _snapshots.value
            SnapshotResult.Success("快照 '$name' 创建成功")
        } catch (e: Exception) {
            SnapshotResult.Failure("创建快照失败: ${e.message}")
        } finally {
            _isOperating.value = false
        }
    }

    /**
     * 回滚到指定快照（真实解包覆盖 rootfs）。
     */
    suspend fun rollbackToSnapshot(snapshotId: String): SnapshotResult = withContext(Dispatchers.IO) {
        _isOperating.value = true
        try {
            val file = snapshotFile(snapshotId)
            if (!file.exists()) return@withContext SnapshotResult.Failure("快照不存在: $snapshotId")

            val root = File(rootfsPath)
            root.listFiles()?.forEach { it.deleteRecursively() }
            root.mkdirs()
            TarExtractor.extract(file, root)

            _snapshots.value = _snapshots.value.map { it.copy(isCurrent = it.id == snapshotId) }
            SnapshotResult.Success("已回滚到快照 '$snapshotId'")
        } catch (e: Exception) {
            SnapshotResult.Failure("回滚失败: ${e.message}")
        } finally {
            _isOperating.value = false
        }
    }

    fun deleteSnapshot(snapshotId: String): SnapshotResult {
        return try {
            val snapshot = _snapshots.value.firstOrNull { it.id == snapshotId }
                ?: return SnapshotResult.Failure("快照不存在: $snapshotId")
            snapshotFile(snapshotId).delete()
            _snapshots.value = _snapshots.value.filterNot { it.id == snapshotId }
            SnapshotResult.Success("快照 '${snapshot.name}' 已删除")
        } catch (e: Exception) {
            SnapshotResult.Failure("删除快照失败: ${e.message}")
        }
    }

    fun renameSnapshot(snapshotId: String, newName: String): SnapshotResult {
        return try {
            _snapshots.value = _snapshots.value.map { s ->
                if (s.id == snapshotId) s.copy(name = newName) else s
            }
            SnapshotResult.Success("快照已重命名为 '$newName'")
        } catch (e: Exception) {
            SnapshotResult.Failure("重命名失败: ${e.message}")
        }
    }

    fun updateSnapshotDescription(snapshotId: String, description: String): SnapshotResult {
        return try {
            _snapshots.value = _snapshots.value.map { s ->
                if (s.id == snapshotId) s.copy(description = description) else s
            }
            SnapshotResult.Success("快照描述已更新")
        } catch (e: Exception) {
            SnapshotResult.Failure("更新描述失败: ${e.message}")
        }
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
        return try {
            val sorted = _snapshots.value.sortedByDescending { it.createdAt }
            if (sorted.size <= keepCount) {
                return SnapshotResult.Success("快照数量未超过限制，无需清理")
            }
            val toDelete = sorted.drop(keepCount)
            toDelete.forEach { snapshotFile(it.id).delete() }
            _snapshots.value = sorted.take(keepCount)
            SnapshotResult.Success("已清理 ${toDelete.size} 个旧快照")
        } catch (e: Exception) {
            SnapshotResult.Failure("清理失败: ${e.message}")
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

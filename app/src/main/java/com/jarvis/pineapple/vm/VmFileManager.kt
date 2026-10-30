package com.jarvis.pineapple.vm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 虚拟机内文件信息
 */
data class VmFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0,
    val lastModified: Long = System.currentTimeMillis(),
    val permissions: String = "rwxr-xr-x"
)

/**
 * 虚拟机文件管理器。
 *
 * 直接操作 [rootfsPath] 下的真实文件系统（guest 路径映射到 rootfs 内的实际路径），
 * 无需 root，支持浏览、增删改、上传下载与搜索。
 */
class VmFileManager(
    private val vmId: String,
    private val rootfsPath: String,
    private val basePath: String = "/"
) {
    private val root: File get() = File(rootfsPath)

    private val _currentPath = MutableStateFlow(basePath)
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _files = MutableStateFlow<List<VmFile>>(emptyList())
    val files: StateFlow<List<VmFile>> = _files.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val navigationHistory = mutableListOf<String>()

    /** guest 路径 -> 宿主机 File（含路径穿越防护） */
    private fun hostFile(guestPath: String): File {
        val rel = guestPath.trimStart('/')
        val file = File(root, rel)
        val canonicalRoot = root.canonicalPath
        val canonical = try { file.canonicalFile } catch (e: Exception) { file }
        return if (canonical.path.startsWith(canonicalRoot)) canonical else root
    }

    /** 宿主机 File -> guest 路径 */
    private fun guestPath(file: File): String {
        val rel = file.canonicalPath.removePrefix(root.canonicalPath)
        return if (rel.isEmpty()) "/" else rel
    }

    fun listFiles(path: String = _currentPath.value): List<VmFile> {
        _isLoading.value = true
        _currentPath.value = path
        val dir = hostFile(path)
        val result = if (dir.isDirectory) {
            dir.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?.map { toVmFile(it) }
                ?: emptyList()
        } else {
            emptyList()
        }
        _files.value = result
        _isLoading.value = false
        return result
    }

    private fun toVmFile(file: File): VmFile = VmFile(
        name = file.name,
        path = guestPath(file),
        isDirectory = file.isDirectory,
        sizeBytes = if (file.isDirectory) dirSize(file) else file.length(),
        lastModified = file.lastModified(),
        permissions = formatPermissions(file)
    )

    private fun formatPermissions(file: File): String {
        val type = if (file.isDirectory) "d" else "-"
        val r = if (file.canRead()) "r" else "-"
        val w = if (file.canWrite()) "w" else "-"
        val x = if (file.canExecute()) "x" else "-"
        return "$type$r$w$x$r$w$x$r$w$x"
    }

    private fun dirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { size += if (it.isDirectory) dirSize(it) else it.length() }
        return size
    }

    fun enterDirectory(dirName: String) {
        val currentPath = _currentPath.value
        val newPath = if (currentPath == "/") "/$dirName" else "$currentPath/$dirName"
        navigationHistory.add(currentPath)
        listFiles(newPath)
    }

    fun navigateTo(path: String) {
        navigationHistory.add(_currentPath.value)
        listFiles(path)
    }

    fun goBack(): Boolean {
        if (navigationHistory.isEmpty()) return false
        val previousPath = navigationHistory.removeAt(navigationHistory.size - 1)
        listFiles(previousPath)
        return true
    }

    fun createDirectory(name: String): Boolean {
        val target = hostFile(joinPath(_currentPath.value, name))
        return try {
            val ok = target.mkdirs()
            listFiles(_currentPath.value)
            ok
        } catch (e: Exception) {
            false
        }
    }

    fun createFile(name: String, content: String = ""): Boolean {
        val target = hostFile(joinPath(_currentPath.value, name))
        return try {
            target.parentFile?.mkdirs()
            target.writeText(content)
            listFiles(_currentPath.value)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(path: String): Boolean {
        val target = hostFile(path)
        val ok = try {
            if (target.isDirectory) target.deleteRecursively() else target.delete()
        } catch (e: Exception) {
            false
        }
        listFiles(_currentPath.value)
        return ok
    }

    fun rename(oldPath: String, newName: String): Boolean {
        val source = hostFile(oldPath)
        val parent = source.parentFile ?: return false
        val dest = File(parent, newName)
        return try {
            val ok = source.renameTo(dest)
            listFiles(_currentPath.value)
            ok
        } catch (e: Exception) {
            false
        }
    }

    fun readFile(path: String, maxChars: Int = 100_000): String {
        val target = hostFile(path)
        if (!target.exists() || target.isDirectory) return ""
        return try {
            val text = target.readText()
            if (text.length > maxChars) text.substring(0, maxChars) + "\n...(已截断)" else text
        } catch (e: Exception) {
            ""
        }
    }

    fun writeFile(path: String, content: String): Boolean {
        val target = hostFile(path)
        return try {
            target.parentFile?.mkdirs()
            target.writeText(content)
            listFiles(_currentPath.value)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 将宿主机文件上传到 guest 指定目录。
     */
    suspend fun uploadFile(localPath: String, remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val source = File(localPath)
            if (!source.exists()) return@withContext false
            val targetDir = hostFile(remotePath)
            val target = if (targetDir.isDirectory) File(targetDir, source.name) else targetDir
            target.parentFile?.mkdirs()
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            listFiles(_currentPath.value)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从 guest 下载文件到宿主机路径。
     */
    suspend fun downloadFile(remotePath: String, localPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val source = hostFile(remotePath)
            if (!source.exists() || source.isDirectory) return@withContext false
            val target = File(localPath)
            target.parentFile?.mkdirs()
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 递归搜索文件名。
     */
    fun searchFiles(query: String, path: String = _currentPath.value): List<VmFile> {
        val start = hostFile(path)
        val result = mutableListOf<VmFile>()
        start.walkTopDown()
            .onEnter { it.name != "proc" && it.name != "sys" && it.name != "dev" }
            .forEach { file ->
                if (file != start && file.name.contains(query, ignoreCase = true)) {
                    result.add(toVmFile(file))
                }
            }
        return result.take(200)
    }

    fun getFileDetails(path: String): VmFile? {
        val target = hostFile(path)
        return if (target.exists()) toVmFile(target) else null
    }

    private fun joinPath(base: String, name: String): String =
        if (base == "/") "/$name" else "$base/$name"
}

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
    val lastModified: Long = System.currentTimeMillis()
)

/**
 * 虚拟机文件管理器。
 *
 * 直接操作 [rootfsPath] 下的真实文件系统（guest 路径映射到 rootfs 内的实际路径），
 * 无需 root，支持浏览、增删改、上传下载与搜索。
 */
class VmFileManager(
    rootfsPath: String,
    private val basePath: String = "/"
) {
    private val root: File = File(rootfsPath).canonicalFile

    private val _currentPath = MutableStateFlow(basePath)
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _files = MutableStateFlow<List<VmFile>>(emptyList())
    val files: StateFlow<List<VmFile>> = _files.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val navigationHistory = mutableListOf<String>()

    /** guest 路径 -> 宿主机 File；越界路径返回 null，绝不回退到根目录。 */
    private fun hostFile(guestPath: String): File? {
        return try {
            val file = File(root, guestPath.trimStart('/')).canonicalFile
            if (isInsideRoot(file)) file else null
        } catch (e: Exception) {
            null
        }
    }

    private fun isInsideRoot(file: File): Boolean =
        file == root || file.path.startsWith(root.path + File.separator)

    /** 宿主机 File -> guest 路径，不解析符号链接的显示路径。 */
    private fun guestPath(file: File): String {
        val relative = file.absoluteFile.relativeTo(root).invariantSeparatorsPath
        return if (relative.isEmpty()) "/" else "/$relative"
    }

    fun listFiles(path: String = _currentPath.value): List<VmFile> {
        _isLoading.value = true
        return try {
            val dir = hostFile(path)
            val result = if (dir?.isDirectory == true) {
                dir.listFiles()
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?.map { toVmFile(it) }
                    ?: emptyList()
            } else {
                emptyList()
            }
            if (dir != null) _currentPath.value = guestPath(dir)
            _files.value = result
            result
        } finally {
            _isLoading.value = false
        }
    }

    private fun toVmFile(file: File): VmFile = VmFile(
        name = file.name,
        path = guestPath(file),
        isDirectory = file.isDirectory,
        sizeBytes = if (file.isDirectory) dirSize(file) else file.length(),
        lastModified = file.lastModified()
    )

    private fun dirSize(dir: File): Long {
        if (isSymbolicLink(dir) || !isInsideRoot(dir.canonicalFile)) return 0
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) dirSize(file) else if (isInsideRoot(file.canonicalFile)) file.length() else 0L
        }
        return size
    }

    private fun isSymbolicLink(file: File): Boolean =
        try { file.canonicalFile != file.absoluteFile } catch (e: Exception) { true }

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
        val target = hostFile(joinPath(_currentPath.value, name)) ?: return false
        return try {
            val ok = target.mkdirs()
            if (ok) listFiles(_currentPath.value)
            ok
        } catch (e: Exception) {
            false
        }
    }

    fun createFile(name: String, content: String = ""): Boolean {
        val target = hostFile(joinPath(_currentPath.value, name)) ?: return false
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
        val target = hostFile(path) ?: return false
        if (target == root) return false
        val ok = try {
            if (target.isDirectory) target.deleteRecursively() else target.delete()
        } catch (e: Exception) {
            false
        }
        if (ok) listFiles(_currentPath.value)
        return ok
    }

    fun rename(oldPath: String, newName: String): Boolean {
        val source = hostFile(oldPath) ?: return false
        if (source == root || newName.isBlank() || newName == "." || newName == ".." || newName.contains('/')) {
            return false
        }
        val parent = source.parentFile ?: return false
        val dest = try { File(parent, newName).canonicalFile } catch (e: Exception) { return false }
        if (!isInsideRoot(dest)) return false
        return try {
            val ok = source.renameTo(dest)
            if (ok) listFiles(_currentPath.value)
            ok
        } catch (e: Exception) {
            false
        }
    }

    fun readFile(path: String, maxChars: Int = 100_000): String {
        val target = hostFile(path) ?: return ""
        if (!target.isFile) return ""
        return try {
            val text = target.readText()
            if (text.length > maxChars) text.substring(0, maxChars) + "\n...(已截断)" else text
        } catch (e: Exception) {
            ""
        }
    }

    fun writeFile(path: String, content: String): Boolean {
        val target = hostFile(path) ?: return false
        return try {
            target.parentFile?.mkdirs()
            target.writeText(content)
            listFiles(_currentPath.value)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 将宿主机文件上传到 guest 指定目录。 */
    suspend fun uploadFile(localPath: String, remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val source = File(localPath)
            if (!source.isFile) return@withContext false
            val targetDir = hostFile(remotePath) ?: return@withContext false
            val target = if (targetDir.isDirectory) File(targetDir, source.name).canonicalFile else targetDir
            if (!isInsideRoot(target)) return@withContext false
            target.parentFile?.mkdirs()
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            listFiles(_currentPath.value)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 从 guest 下载文件到宿主机路径。 */
    suspend fun downloadFile(remotePath: String, localPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val source = hostFile(remotePath) ?: return@withContext false
            if (!source.isFile) return@withContext false
            val target = File(localPath)
            target.parentFile?.mkdirs()
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 递归搜索文件名，不跟随符号链接或进入 rootfs 外目录。 */
    fun searchFiles(query: String, path: String = _currentPath.value): List<VmFile> {
        val start = hostFile(path) ?: return emptyList()
        val result = mutableListOf<VmFile>()
        start.walkTopDown()
            .onEnter { file ->
                file.name != "proc" && file.name != "sys" && file.name != "dev" &&
                    !isSymbolicLink(file) && isInsideRoot(file.canonicalFile)
            }
            .forEach { file ->
                if (file != start && file.name.contains(query, ignoreCase = true)) {
                    result.add(toVmFile(file))
                }
            }
        return result.take(200)
    }

    fun getFileDetails(path: String): VmFile? {
        val target = hostFile(path) ?: return null
        return if (target.exists()) toVmFile(target) else null
    }

    private fun joinPath(base: String, name: String): String =
        if (base == "/") "/$name" else "$base/$name"
}

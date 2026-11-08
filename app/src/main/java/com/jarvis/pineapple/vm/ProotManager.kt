package com.jarvis.pineapple.vm

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * PRoot 管理器。
 *
 * 基于 PRoot（用户态 chroot）在 Android 上运行完整的 Linux 发行版，
 * 无需 root 权限。功能：
 * - 下载并安装与设备 ABI 匹配的静态 PRoot 二进制（SHA-256 校验）
 * - 下载并解压 aarch64 / arm Linux rootfs
 * - 在 rootfs 内执行真实命令（ProcessBuilder + 洁净环境）
 * - 软件包安装 / 卸载 / 更新（apk / apt / pacman / xbps）
 * - 文件在宿主机与 guest 之间复制
 *
 * 所有操作均为真实实现，不依赖任何模拟输出。
 */
class ProotManager internal constructor(
    private val filesDir: File,
    private val cacheDir: File,
    private val abiProvider: () -> Array<String> = { Build.SUPPORTED_ABIS }
) {

    constructor(context: Context) : this(context.filesDir, context.cacheDir)

    private val prootDir = File(filesDir, "proot")
    private val rootfsDir = File(filesDir, "rootfs")
    private val binDir = File(prootDir, "bin")

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    init {
        if (!prootDir.exists()) prootDir.mkdirs()
        if (!rootfsDir.exists()) rootfsDir.mkdirs()
        if (!binDir.exists()) binDir.mkdirs()
        _isReady.value = isProotInstalled()
    }

    /**
     * PRoot 运行配置
     */
    data class ProotConfig(
        val rootfsPath: String,
        val binds: List<BindMount> = defaultBinds(),
        val env: Map<String, String> = defaultEnv(),
        val workingDir: String = "/root",
        val kernelRelease: String = "6.1.0",
        val asRoot: Boolean = true
    )

    /**
     * 绑定挂载
     */
    data class BindMount(
        val hostPath: String,
        val guestPath: String,
        val isReadOnly: Boolean = false
    )

    /**
     * 命令执行结果
     */
    data class CommandResult(
        val command: String,
        val output: String,
        val exitCode: Int,
        val executionTimeMs: Long
    )

    companion object {
        const val PROOT_VERSION = "2.3.1"

        private const val GH_PROXY = "https://ghproxy.net/"
        private const val RAW = "https://raw.githubusercontent.com/proot-me/proot-static-build/master/static/"

        /** PRoot aarch64 静态二进制的 SHA-256（用于完整性校验）。 */
        const val PROOT_SHA256_ARM64 = "24121ee27c9d006c318b7ed3114b2099d55fff160e6d3ab20132db59fddecfda"

        val PROOT_URLS_ARM64 = listOf(
            GH_PROXY + RAW + "proot-arm64",
            RAW + "proot-arm64",
            "https://cdn.jsdelivr.net/gh/proot-me/proot-static-build@master/static/proot-arm64"
        )

        fun defaultBinds(): List<BindMount> = listOf(
            BindMount("/dev", "/dev"),
            BindMount("/proc", "/proc"),
            BindMount("/sys", "/sys"),
            BindMount("/sdcard", "/sdcard")
        )

        fun defaultEnv(): Map<String, String> = mapOf(
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "MOZ_FAKE_NO_SANDBOX" to "1"
        )

        fun requireArm64(abi: String) {
            if (!abi.startsWith("arm64")) {
                val shown = abi.ifBlank { "unknown" }
                throw IllegalStateException("当前 ABI 为 $shown，仅支持 arm64-v8a 的 PRoot 二进制")
            }
        }
    }

    private fun prootBinary(): File = File(binDir, "proot")

    /**
     * 初始化 PRoot：确保二进制已就绪。
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isProotInstalled()) {
                downloadProotBinary()
            }
            _isReady.value = isProotInstalled()
            _isReady.value
        } catch (e: Exception) {
            _isReady.value = false
            false
        }
    }

    /**
     * 下载 PRoot 二进制（多镜像回退 + SHA-256 校验）。
     */
    fun resolveSupportedAbi(): String {
        val abi = abiProvider().firstOrNull().orEmpty()
        requireArm64(abi)
        return abi
    }

    private suspend fun downloadProotBinary() = withContext(Dispatchers.IO) {
        val target = prootBinary()
        resolveSupportedAbi()
        val urls = PROOT_URLS_ARM64
        val expectedSha = PROOT_SHA256_ARM64

        var lastError: Exception? = null
        for (url in urls) {
            try {
                val tmp = File(binDir, "proot.tmp")
                downloadToFile(url, tmp)
                val actual = TarExtractor.sha256(tmp)
                if (!actual.equals(expectedSha, ignoreCase = true)) {
                    tmp.delete()
                    lastError = IllegalStateException("PRoot 校验失败: $actual")
                    continue
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                target.setExecutable(true, false)
                return@withContext
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("无法下载 PRoot 二进制")
    }

    /**
     * 下载 rootfs 归档并解压到目标目录。
     */
    suspend fun installRootfs(archiveUrl: String, targetDir: File, onProgress: (Float) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!targetDir.exists()) targetDir.mkdirs()
                val tempFile = File(cacheDir, "rootfs_${System.currentTimeMillis()}.archive")
                downloadToFile(archiveUrl, tempFile, onProgress)
                TarExtractor.extract(tempFile, targetDir)
                tempFile.delete()
                true
            } catch (e: Exception) {
                false
            }
        }

    /**
     * 从本地归档安装 rootfs。
     */
    suspend fun installRootfsFromArchive(archive: File, targetDir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!targetDir.exists()) targetDir.mkdirs()
            TarExtractor.extract(archive, targetDir)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 在 rootfs 中执行命令。
     */
    suspend fun executeCommand(config: ProotConfig, command: String): CommandResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        if (!isProotInstalled()) {
            return@withContext CommandResult(command, "Error: PRoot 未安装", -1, 0)
        }
        try {
            val shell = detectShell(config.rootfsPath)
            val args = mutableListOf<String>()
            args.add(prootBinary().absolutePath)
            args.add("-r"); args.add(config.rootfsPath)
            if (config.asRoot) args.add("-0")
            args.add("-w"); args.add(config.workingDir)
            if (config.kernelRelease.isNotBlank()) {
                args.add("-k"); args.add(config.kernelRelease)
            }
            for (bind in config.binds) {
                val host = File(bind.hostPath)
                if (!host.exists()) continue
                args.add("-b"); args.add("${bind.hostPath}:${bind.guestPath}")
            }
            // 选用可真实执行的 shell（proot 2.x 直接执行 busybox 符号链接会失败）
            args.addAll(shell)
            args.add("-c"); args.add(command)

            val pb = ProcessBuilder(args)
            val env = pb.environment()
            env.clear()
            env.putAll(config.env)
            env["PROOT_TMP_DIR"] = cacheDir.absolutePath
            env["PROOT_NO_SECCOMP"] = "1"
            pb.directory(cacheDir)
            pb.redirectErrorStream(true)

            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            CommandResult(
                command = command,
                output = output,
                exitCode = exitCode,
                executionTimeMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            CommandResult(
                command = command,
                output = "Error: ${e.message}",
                exitCode = -1,
                executionTimeMs = System.currentTimeMillis() - start
            )
        }
    }

    /**
     * 在 rootfs 中探测可用的真实 shell 命令。
     * 返回用于 ProcessBuilder 的前缀 argv（如 ["/bin/bash"] 或 ["/bin/busybox","sh"]）。
     */
    private fun detectShell(rootfsPath: String): List<String> {
        val root = File(rootfsPath)
        val bash = File(root, "bin/bash")
        if (bash.exists() && isRealBinary(bash)) return listOf("/bin/bash")
        val busybox = File(root, "bin/busybox")
        if (busybox.exists()) return listOf("/bin/busybox", "sh")
        val sh = File(root, "bin/sh")
        if (sh.exists()) return listOf("/bin/sh")
        return listOf("/bin/sh")
    }

    private fun isRealBinary(file: File): Boolean {
        return try {
            val mode = android.system.Os.lstat(file.absolutePath).st_mode
            !android.system.OsConstants.S_ISLNK(mode)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * 软件包安装。
     */
    suspend fun installPackage(config: ProotConfig, packageManager: String, packageName: String): Boolean {
        val command = when (packageManager.lowercase()) {
            "apk" -> "apk add $packageName"
            "apt" -> "apt-get update && apt-get install -y $packageName"
            "pacman" -> "pacman -S --noconfirm $packageName"
            "xbps" -> "xbps-install -y $packageName"
            else -> return false
        }
        return executeCommand(config, command).exitCode == 0
    }

    /**
     * 软件包卸载。
     */
    suspend fun uninstallPackage(config: ProotConfig, packageManager: String, packageName: String): Boolean {
        val command = when (packageManager.lowercase()) {
            "apk" -> "apk del $packageName"
            "apt" -> "apt-get remove -y $packageName"
            "pacman" -> "pacman -R --noconfirm $packageName"
            "xbps" -> "xbps-remove -y $packageName"
            else -> return false
        }
        return executeCommand(config, command).exitCode == 0
    }

    /**
     * 软件包更新。
     */
    suspend fun updatePackages(config: ProotConfig, packageManager: String): Boolean {
        val command = when (packageManager.lowercase()) {
            "apk" -> "apk update && apk upgrade"
            "apt" -> "apt-get update && apt-get upgrade -y"
            "pacman" -> "pacman -Syu --noconfirm"
            "xbps" -> "xbps-install -Su"
            else -> return false
        }
        return executeCommand(config, command).exitCode == 0
    }

    /**
     * 列出已安装软件包。
     */
    suspend fun listInstalledPackages(config: ProotConfig, packageManager: String): List<String> {
        val command = when (packageManager.lowercase()) {
            "apk" -> "apk info"
            "apt" -> "dpkg-query -W -f='\${Package}\\n'"
            "pacman" -> "pacman -Qq"
            "xbps" -> "xbps-query -l"
            else -> return emptyList()
        }
        val result = executeCommand(config, command)
        if (result.exitCode == 0) {
            return result.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    /**
     * 复制文件到 guest 系统。
     */
    suspend fun copyToGuest(config: ProotConfig, hostPath: String, guestPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val hostFile = File(hostPath)
            if (!hostFile.exists()) return@withContext false
            val guestFile = File(config.rootfsPath, guestPath.trimStart('/'))
            guestFile.parentFile?.mkdirs()
            hostFile.inputStream().use { input ->
                FileOutputStream(guestFile).use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从 guest 系统复制文件。
     */
    suspend fun copyFromGuest(config: ProotConfig, guestPath: String, hostPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val guestFile = File(config.rootfsPath, guestPath.trimStart('/'))
            if (!guestFile.exists()) return@withContext false
            val hostFile = File(hostPath)
            hostFile.parentFile?.mkdirs()
            guestFile.inputStream().use { input ->
                FileOutputStream(hostFile).use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isProotInstalled(): Boolean {
        val binary = prootBinary()
        return binary.exists() && binary.length() > 0
    }

    /** PRoot 版本字符串。 */
    suspend fun getProotVersion(): String {
        return try {
            val process = ProcessBuilder(prootBinary().absolutePath, "--version")
                .redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            out.lineSequence().firstOrNull()?.trim() ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /** rootfs 存储根目录。 */
    fun rootfsRoot(): File = rootfsDir

    /**
     * 下载任意文件到目标路径（公开封装，供发行版/镜像下载复用）。
     */
    suspend fun downloadArchive(url: String, target: File, onProgress: (Float) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            downloadToFile(url, target, onProgress)
        }

    /** 获取已下载的 rootfs 列表。 */
    fun getDownloadedRootfs(): List<String> {
        return rootfsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    }

    /** 删除 rootfs。 */
    fun deleteRootfs(name: String): Boolean {
        val dir = File(rootfsDir, name)
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    /** 获取 rootfs 大小（字节）。 */
    fun getRootfsSize(name: String): Long {
        val dir = File(rootfsDir, name)
        return if (dir.exists()) calculateDirSize(dir) else 0
    }

    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) calculateDirSize(file) else file.length()
        }
        return size
    }

    /**
     * 通用文件下载（带进度回调）。
     */
    private fun downloadToFile(url: String, target: File, onProgress: (Float) -> Unit = {}) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "JARVIS/1.0")
        connection.connect()

        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                var read: Int
                var totalRead = 0L
                while (true) {
                    read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    totalRead += read
                    val p = if (total > 0) totalRead.toFloat() / total else 0f
                    _downloadProgress.value = p
                    onProgress(p)
                }
            }
        }
        connection.disconnect()
    }
}

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
    private val abiProvider: () -> Array<String> = { Build.SUPPORTED_ABIS },
    private val symbolicLinkChecker: (File) -> Boolean = { file ->
        android.system.OsConstants.S_ISLNK(android.system.Os.lstat(file.absolutePath).st_mode)
    }
) {

    constructor(context: Context) : this(context.filesDir, context.cacheDir)

    private val prootDir = File(filesDir, "proot")
    private val rootfsDir = File(filesDir, "rootfs")
    private val binDir = File(prootDir, "bin")

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val sessionLock = Any()
    private val sessions = mutableMapOf<String, Process>()
    private val backgrounds = mutableMapOf<String, Process>()

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

        internal fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"

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
                if (!target.setExecutable(true, false) || !target.canExecute()) {
                    throw IllegalStateException("无法设置 PRoot 可执行权限")
                }
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
            val process = commandProcess(config, command).start()
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

    private fun commandProcess(config: ProotConfig, command: String): ProcessBuilder {
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
        args.addAll(detectShell(config.rootfsPath))
        args.add("-c"); args.add(command)

        val pb = ProcessBuilder(args)
        val env = pb.environment()
        env.clear()
        env.putAll(config.env)
        env["PROOT_TMP_DIR"] = cacheDir.absolutePath
        env["PROOT_NO_SECCOMP"] = "1"
        pb.directory(cacheDir)
        pb.redirectErrorStream(true)
        return pb
    }

    /** 启动并登记一个真实的长运行 PRoot 会话。 */
    suspend fun startSession(config: ProotConfig): Boolean = withContext(Dispatchers.IO) {
        if (!isProotInstalled()) return@withContext false
        synchronized(sessionLock) {
            val key = sessionKey(config)
            val existing = sessions[key]
            if (existing != null && isProcessAlive(existing)) return@synchronized true
            sessions.remove(key)
            try {
                val process = commandProcess(config, "exec sleep 2147483647").start()
                // API 24/25 没有 redirectOutput(File)，后台排空输出以避免子进程阻塞。
                Thread {
                    process.inputStream.use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        do {
                            val bytesRead = input.read(buffer)
                        } while (bytesRead != -1)
                    }
                }.apply {
                    name = "proot-session-output"
                    isDaemon = true
                    start()
                }
                if (isProcessAlive(process)) {
                    sessions[key] = process
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    /** 停止会话并确认进程已经退出；没有存活进程时目标状态已经达成。 */
    suspend fun stopSession(config: ProotConfig): Boolean = withContext(Dispatchers.IO) {
        val key = sessionKey(config)
        val process = synchronized(sessionLock) {
            val current = sessions[key]
            if (current != null && !isProcessAlive(current)) {
                sessions.remove(key)
                null
            } else {
                current
            }
        } ?: return@withContext true

        process.destroy()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (isProcessAlive(process) && System.nanoTime() < deadline) {
            Thread.sleep(25)
        }
        val stopped = !isProcessAlive(process)
        if (stopped) {
            synchronized(sessionLock) { sessions.remove(key) }
        }
        stopped
    }

    private fun sessionKey(config: ProotConfig): String = File(config.rootfsPath).canonicalPath

    /**
     * 在 PRoot 中启动一个长期驻留的后台进程（如 Xvfb + x11vnc 桌面会话、GUI 浏览器）。
     * 与 [startSession] 分开登记，避免覆盖同 rootfs 的普通会话。
     */
    suspend fun launchBackground(config: ProotConfig, key: String, command: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isProotInstalled()) return@withContext false
            synchronized(sessionLock) {
                val existing = backgrounds[key]
                if (existing != null && isProcessAlive(existing)) return@synchronized true
                backgrounds.remove(key)
                try {
                    val process = commandProcess(config, command).start()
                    Thread {
                        process.inputStream.use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (input.read(buffer) != -1) {
                                // 后台输出持续排空，避免子进程写满管道阻塞。
                            }
                        }
                    }.apply {
                        name = "proot-bg-$key"
                        isDaemon = true
                        start()
                    }
                    if (isProcessAlive(process)) {
                        backgrounds[key] = process
                        true
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    false
                }
            }
        }

    /** 停止后台进程并确认退出；没有存活进程时目标状态已经达成。 */
    suspend fun stopBackground(key: String): Boolean = withContext(Dispatchers.IO) {
        val process = synchronized(sessionLock) {
            val current = backgrounds[key]
            if (current != null && !isProcessAlive(current)) {
                backgrounds.remove(key)
                null
            } else {
                current
            }
        } ?: return@withContext true

        process.destroy()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (isProcessAlive(process) && System.nanoTime() < deadline) {
            Thread.sleep(25)
        }
        val stopped = !isProcessAlive(process)
        if (stopped) {
            synchronized(sessionLock) { backgrounds.remove(key) }
        }
        stopped
    }

    /** 后台进程是否仍在运行。 */
    fun isBackgroundRunning(key: String): Boolean = synchronized(sessionLock) {
        backgrounds[key]?.let { isProcessAlive(it) } == true
    }

    private fun isProcessAlive(process: Process): Boolean {
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
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

    internal fun isRealBinary(file: File): Boolean {
        return try {
            !symbolicLinkChecker(file)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 软件包安装。
     */
    suspend fun installPackage(config: ProotConfig, packageManager: String, packageName: String): Boolean {
        val packageArg = shellQuote(packageName)
        val command = when (packageManager.lowercase()) {
            "apk" -> "apk add $packageArg"
            "apt" -> "apt-get update && apt-get install -y $packageArg"
            "pacman" -> "pacman -S --noconfirm $packageArg"
            "xbps" -> "xbps-install -y $packageArg"
            else -> return false
        }
        return executeCommand(config, command).exitCode == 0
    }

    /**
     * 软件包卸载。
     */
    suspend fun uninstallPackage(config: ProotConfig, packageManager: String, packageName: String): Boolean {
        val packageArg = shellQuote(packageName)
        val command = when (packageManager.lowercase()) {
            "apk" -> "apk del $packageArg"
            "apt" -> "apt-get remove -y $packageArg"
            "pacman" -> "pacman -R --noconfirm $packageArg"
            "xbps" -> "xbps-remove -y $packageArg"
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
    suspend fun listInstalledPackages(config: ProotConfig, packageManager: String): CommandResult {
        val command = when (packageManager.lowercase()) {
            "apk" -> "apk info"
            "apt" -> "dpkg-query -W -f='\${Package}\\n'"
            "pacman" -> "pacman -Qq"
            "xbps" -> "xbps-query -l"
            else -> return CommandResult("", "Error: 不支持的包管理器 $packageManager", -1, 0)
        }
        return executeCommand(config, command)
    }

    /**
     * 复制文件到 guest 系统。
     */
    suspend fun copyToGuest(config: ProotConfig, hostPath: String, guestPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val hostFile = File(hostPath)
            if (!hostFile.isFile) return@withContext false
            val guestFile = resolveGuestFile(config, guestPath) ?: return@withContext false
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
            val guestFile = resolveGuestFile(config, guestPath) ?: return@withContext false
            if (!guestFile.isFile) return@withContext false
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

    private fun resolveGuestFile(config: ProotConfig, guestPath: String): File? {
        return try {
            val root = File(config.rootfsPath).canonicalFile
            val target = File(root, guestPath.trimStart('/')).canonicalFile
            if (target == root || target.path.startsWith(root.path + File.separator)) target else null
        } catch (e: Exception) {
            null
        }
    }

    fun isProotInstalled(): Boolean {
        val binary = prootBinary()
        return binary.isFile && binary.length() > 0 && binary.canExecute()
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

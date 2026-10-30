package com.jarvis.pineapple.vm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Linux 发行版信息
 */
data class DistroInfo(
    val id: String,
    val name: String,
    val version: String,
    val architecture: String = "aarch64",
    val downloadUrl: String = "",
    val mirrorUrls: List<String> = emptyList(),
    val sizeBytes: Long = 0,
    val checksumSha256: String = "",
    val category: DistroCategory = DistroCategory.GENERAL,
    val description: String = "",
    val packageManager: String = "",
    val isDownloaded: Boolean = false,
    val localPath: String = "",
    val recommendedCpuCores: Int = 2,
    val recommendedMemoryMb: Int = 2048,
    val recommendedDiskGb: Int = 20
)

enum class DistroCategory {
    GENERAL, SECURITY, SERVER, DESKTOP, LIGHTWEIGHT, IOT
}

/**
 * 下载进度
 */
data class DownloadProgress(
    val distroId: String,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val currentUrl: String = "",
    val errorMessage: String = ""
)

enum class DownloadStatus {
    PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, VERIFYING
}

/**
 * 虚拟机发行版管理器（REQ-02）。
 *
 * 负责 aarch64 Linux rootfs 的目录管理、下载、SHA-256 校验与解压。
 * 所有下载均为真实网络请求，解压使用 [TarExtractor]。
 */
class VmDistroManager(
    private val context: Context,
    private val prootManager: ProotManager
) {

    private val _distros = MutableStateFlow<List<DistroInfo>>(emptyList())
    val distros: StateFlow<List<DistroInfo>> = _distros.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadProgress>> = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    init {
        _distros.value = buildCatalog()
        refreshDownloadState()
    }

    // ==================== 查询 ====================

    fun getAvailableDistros(): List<DistroInfo> = _distros.value

    fun findDistro(distroId: String): DistroInfo? = _distros.value.firstOrNull { it.id == distroId }

    fun getDistrosByCategory(category: DistroCategory): List<DistroInfo> =
        _distros.value.filter { it.category == category }

    fun searchDistros(query: String): List<DistroInfo> = _distros.value.filter {
        it.name.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true)
    }

    fun getDownloadedDistros(): List<DistroInfo> = _distros.value.filter { it.isDownloaded }

    fun rootfsDir(distroId: String): File = File(prootManager.rootfsRoot(), distroId)

    fun getRootfsPath(distroId: String): String? {
        val dir = rootfsDir(distroId)
        return if (dir.exists()) dir.absolutePath else null
    }

    fun getPackageManager(distroId: String): String = findDistro(distroId)?.packageManager ?: ""

    fun sizeOnDisk(distroId: String): Long = prootManager.getRootfsSize(distroId)

    /** 依据实际文件系统状态刷新“已下载”标记。 */
    fun refreshDownloadState() {
        _distros.value = _distros.value.map { d ->
            val dir = rootfsDir(d.id)
            val downloaded = dir.exists() && (dir.listFiles()?.isNotEmpty() == true)
            d.copy(isDownloaded = downloaded, localPath = if (downloaded) dir.absolutePath else "")
        }
    }

    // ==================== 下载 / 删除 ====================

    /**
     * 下载并解压指定发行版。
     * 依次尝试主地址与备用镜像，校验 SHA-256（若提供），成功后解压到 rootfs 目录。
     */
    suspend fun downloadDistro(distroId: String): Boolean = withContext(Dispatchers.IO) {
        val distro = findDistro(distroId) ?: return@withContext false
        if (distro.isDownloaded) return@withContext true

        _isDownloading.value = true
        val targetDir = rootfsDir(distroId)

        try {
            val urls = (listOf(distro.downloadUrl) + distro.mirrorUrls).filter { it.isNotBlank() }
            if (urls.isEmpty()) {
                fail(distroId, "没有可用的下载地址")
                return@withContext false
            }

            var success = false
            var lastError = ""

            for (url in urls) {
                val archive = File(context.cacheDir, "${distroId}_${System.currentTimeMillis()}.archive")
                try {
                    updateProgress(
                        DownloadProgress(
                            distroId = distroId,
                            status = DownloadStatus.DOWNLOADING,
                            totalBytes = distro.sizeBytes,
                            currentUrl = url
                        )
                    )

                    prootManager.downloadArchive(url, archive) { p ->
                        updateProgress(
                            DownloadProgress(
                                distroId = distroId,
                                progress = p,
                                downloadedBytes = (archive.length()).coerceAtLeast(0),
                                totalBytes = if (distro.sizeBytes > 0) distro.sizeBytes else archive.length(),
                                status = DownloadStatus.DOWNLOADING,
                                currentUrl = url
                            )
                        )
                    }

                    // SHA-256 校验
                    if (distro.checksumSha256.isNotBlank()) {
                        updateProgress(
                            DownloadProgress(
                                distroId = distroId,
                                progress = 1f,
                                status = DownloadStatus.VERIFYING,
                                totalBytes = archive.length(),
                                downloadedBytes = archive.length(),
                                currentUrl = url
                            )
                        )
                        val actual = TarExtractor.sha256(archive)
                        if (!actual.equals(distro.checksumSha256, ignoreCase = true)) {
                            lastError = "SHA-256 校验失败（期望 ${distro.checksumSha256}, 实际 $actual）"
                            archive.delete()
                            continue
                        }
                    }

                    // 解压
                    updateProgress(
                        DownloadProgress(
                            distroId = distroId,
                            progress = 0.98f,
                            status = DownloadStatus.VERIFYING,
                            currentUrl = url,
                            errorMessage = ""
                        )
                    )
                    if (targetDir.exists()) targetDir.deleteRecursively()
                    targetDir.mkdirs()
                    TarExtractor.extract(archive, targetDir)
                    archive.delete()
                    success = true
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: e.toString()
                    if (archive.exists()) archive.delete()
                }
            }

            if (success) {
                refreshDownloadState()
                updateProgress(
                    DownloadProgress(
                        distroId = distroId,
                        progress = 1f,
                        status = DownloadStatus.COMPLETED,
                        downloadedBytes = distro.sizeBytes,
                        totalBytes = distro.sizeBytes
                    )
                )
                true
            } else {
                fail(distroId, lastError.ifBlank { "下载失败" })
                false
            }
        } catch (e: Exception) {
            fail(distroId, e.message ?: e.toString())
            false
        } finally {
            _isDownloading.value = false
        }
    }

    fun pauseDownload(distroId: String) {
        updateProgress(
            DownloadProgress(distroId = distroId, status = DownloadStatus.PAUSED)
        )
    }

    suspend fun resumeDownload(distroId: String): Boolean {
        val progress = _downloadProgress.value[distroId]
        if (progress?.status != DownloadStatus.PAUSED) return false
        return downloadDistro(distroId)
    }

    /** 删除已下载的发行版 rootfs。 */
    fun deleteDistro(distroId: String): Boolean {
        val deleted = prootManager.deleteRootfs(distroId)
        _downloadProgress.value = _downloadProgress.value - distroId
        refreshDownloadState()
        return deleted
    }

    fun getDownloadProgress(distroId: String): DownloadProgress? = _downloadProgress.value[distroId]

    private fun updateProgress(progress: DownloadProgress) {
        _downloadProgress.value = _downloadProgress.value + (progress.distroId to progress)
    }

    private fun fail(distroId: String, message: String) {
        updateProgress(
            DownloadProgress(
                distroId = distroId,
                status = DownloadStatus.FAILED,
                errorMessage = message
            )
        )
    }

    // ==================== 发行版目录 ====================

    private fun buildCatalog(): List<DistroInfo> = listOf(
        DistroInfo(
            id = "kali_rolling",
            name = "Kali Linux",
            version = "rolling",
            architecture = "arm64",
            downloadUrl = "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz",
            mirrorUrls = listOf(
                "https://mirrors.tuna.tsinghua.edu.cn/kali/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz",
                "https://mirrors.ustc.edu.cn/kali/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz"
            ),
            sizeBytes = 340_000_000L,
            category = DistroCategory.SECURITY,
            description = "Kali Linux（NetHunter 最小 rootfs）：渗透测试与安全研究首选，含全套安全工具（REQ-02 首发必选）",
            packageManager = "apt",
            recommendedCpuCores = 2,
            recommendedMemoryMb = 2048,
            recommendedDiskGb = 16
        ),
        DistroInfo(
            id = "alpine_3_20",
            name = "Alpine Linux",
            version = "3.20.3",
            architecture = "aarch64",
            downloadUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
            mirrorUrls = listOf(
                "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
                "https://mirrors.ustc.edu.cn/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"
            ),
            sizeBytes = 3_947_906L,
            checksumSha256 = "041fa34a81788242df9e78fa69b97ab45b8ec47ddbf88864755610414a7bf3de",
            category = DistroCategory.LIGHTWEIGHT,
            description = "极轻量发行版，使用 musl 与 BusyBox，适合容器与嵌入式场景",
            packageManager = "apk",
            recommendedCpuCores = 1,
            recommendedMemoryMb = 512,
            recommendedDiskGb = 5
        ),
        DistroInfo(
            id = "ubuntu_24_04",
            name = "Ubuntu",
            version = "24.04 LTS",
            architecture = "arm64",
            downloadUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
            mirrorUrls = listOf(
                "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
            ),
            sizeBytes = 29_936_675L,
            checksumSha256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
            category = DistroCategory.SERVER,
            description = "最流行的 Linux 发行版，LTS 长期支持，软件生态完善",
            packageManager = "apt",
            recommendedCpuCores = 2,
            recommendedMemoryMb = 2048,
            recommendedDiskGb = 12
        ),
        DistroInfo(
            id = "archlinuxarm",
            name = "Arch Linux ARM",
            version = "latest",
            architecture = "aarch64",
            downloadUrl = "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz",
            mirrorUrls = listOf(
                "https://mirrors.tuna.tsinghua.edu.cn/archlinuxarm/os/ArchLinuxARM-aarch64-latest.tar.gz"
            ),
            sizeBytes = 70_913_104L,
            category = DistroCategory.GENERAL,
            description = "滚动更新、高度可定制的发行版，适合高级用户",
            packageManager = "pacman",
            recommendedCpuCores = 2,
            recommendedMemoryMb = 2048,
            recommendedDiskGb = 15
        ),
        DistroInfo(
            id = "voidlinux",
            name = "Void Linux",
            version = "20250202",
            architecture = "aarch64",
            downloadUrl = "https://repo-default.voidlinux.org/live/current/void-aarch64-ROOTFS-20250202.tar.xz",
            mirrorUrls = listOf(
                "https://mirrors.tuna.tsinghua.edu.cn/voidlinux/live/current/void-aarch64-ROOTFS-20250202.tar.xz"
            ),
            sizeBytes = 2_424_076L,
            category = DistroCategory.LIGHTWEIGHT,
            description = "独立发行版，使用 runit 初始化系统，轻量且快速",
            packageManager = "xbps",
            recommendedCpuCores = 1,
            recommendedMemoryMb = 1024,
            recommendedDiskGb = 8
        )
    )
}

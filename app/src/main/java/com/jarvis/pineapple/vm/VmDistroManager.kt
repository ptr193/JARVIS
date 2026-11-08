package com.jarvis.pineapple.vm

import android.content.Context
import com.jarvis.pineapple.net.DownloadMirrors
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
    val recommendedDiskGb: Int = 20,
    val emulatedX86: Boolean = false,
    val unavailableReason: String? = null
)

enum class DistroCategory {
    GENERAL, SECURITY, SERVER, DESKTOP, LIGHTWEIGHT, IOT
}

const val KALI_MINIMAL_ARM64_SHA256 = "96aeced81a857a987715a14417af05c2934e3e19052d6151c398a1c215448b60"

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
        _distros.value = catalog()
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
        if (distro.unavailableReason != null) {
            fail(distroId, distro.unavailableReason)
            return@withContext false
        }
        if (distro.isDownloaded) return@withContext true

        _isDownloading.value = true
        val targetDir = rootfsDir(distroId)

        try {
            val urls = DownloadMirrors.candidates(distro.downloadUrl, distro.mirrorUrls)
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

                    val expectedSha = distro.checksumSha256.ifBlank { fetchSidecarSha256(url) }.orEmpty()
                    if (expectedSha.isBlank()) {
                        lastError = "发行版缺少 SHA-256 校验和，拒绝安装"
                        archive.delete()
                        continue
                    }
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
                    if (!actual.equals(expectedSha, ignoreCase = true)) {
                        lastError = "SHA-256 校验失败（期望 $expectedSha, 实际 $actual）"
                        archive.delete()
                        continue
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
                    lastError = if (url != urls.first()) {
                        "${DownloadMirrors.SWITCHING}；${e.message ?: e.toString()}"
                    } else {
                        e.message ?: e.toString()
                    }
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

    private suspend fun fetchSidecarSha256(archiveUrl: String): String? {
        val sidecarUrls = DownloadMirrors.candidates("$archiveUrl.sha256") +
            DownloadMirrors.candidates("$archiveUrl.sha256.txt")
        for (sidecar in sidecarUrls) {
            try {
                val tmp = File(context.cacheDir, "sha_${System.currentTimeMillis()}.txt")
                prootManager.downloadArchive(sidecar, tmp)
                val parsed = DownloadMirrors.parseSha256Sidecar(tmp.readText())
                tmp.delete()
                if (!parsed.isNullOrBlank()) return parsed
            } catch (_: Exception) {
            }
        }
        return null
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

    companion object {
        const val X86_UNAVAILABLE = "当前设备无法模拟 x86_64"
        const val ROCKY_IMPORT = "Rocky Linux 请通过「导入镜像」安装"
        const val WINDOWS_IMPORT = "Windows 请通过「导入镜像」安装"

        fun x86UnavailableReason(emulationReady: Boolean): String? =
            if (emulationReady) null else X86_UNAVAILABLE

        fun buildCatalog(emulationReady: Boolean = false): List<DistroInfo> {
            val x86Reason = x86UnavailableReason(emulationReady)
            return listOf(
                DistroInfo(
                    id = "debian_12",
                    name = "Debian",
                    version = "12",
                    architecture = "aarch64",
                    downloadUrl = "https://github.com/termux/proot-distro/releases/download/v4.27.0/debian-bookworm-aarch64-pd-v4.27.0.tar.xz",
                    sizeBytes = 95_000_000L,
                    category = DistroCategory.SERVER,
                    description = "Debian 12 bookworm，稳定服务器发行版",
                    packageManager = "apt",
                    recommendedCpuCores = 2,
                    recommendedMemoryMb = 1024,
                    recommendedDiskGb = 8
                ),
                DistroInfo(
                    id = "alpine_3_21",
                    name = "Alpine Linux",
                    version = "3.21",
                    architecture = "aarch64",
                    downloadUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz",
                    mirrorUrls = listOf(
                        "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz"
                    ),
                    sizeBytes = 4_200_000L,
                    category = DistroCategory.LIGHTWEIGHT,
                    description = "Alpine 3.21 极轻量 rootfs",
                    packageManager = "apk",
                    recommendedCpuCores = 1,
                    recommendedMemoryMb = 512,
                    recommendedDiskGb = 4
                ),
                DistroInfo(
                    id = "debian_13",
                    name = "Debian",
                    version = "13",
                    architecture = "aarch64",
                    downloadUrl = "https://github.com/termux/proot-distro/releases/download/v4.27.0/debian-trixie-aarch64-pd-v4.27.0.tar.xz",
                    sizeBytes = 98_000_000L,
                    category = DistroCategory.SERVER,
                    description = "Debian 13 trixie",
                    packageManager = "apt",
                    recommendedCpuCores = 2,
                    recommendedMemoryMb = 1024,
                    recommendedDiskGb = 8
                ),
                DistroInfo(
                    id = "ubuntu_24_04",
                    name = "Ubuntu",
                    version = "24.04 LTS",
                    architecture = "aarch64",
                    downloadUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
                    mirrorUrls = listOf(
                        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
                    ),
                    sizeBytes = 29_936_675L,
                    checksumSha256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
                    category = DistroCategory.SERVER,
                    description = "Ubuntu 24.04 LTS",
                    packageManager = "apt",
                    recommendedCpuCores = 2,
                    recommendedMemoryMb = 2048,
                    recommendedDiskGb = 12
                ),
                DistroInfo(
                    id = "archlinux",
                    name = "Arch Linux",
                    version = "latest",
                    architecture = "aarch64",
                    downloadUrl = "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz",
                    mirrorUrls = listOf(
                        "https://mirrors.tuna.tsinghua.edu.cn/archlinuxarm/os/ArchLinuxARM-aarch64-latest.tar.gz"
                    ),
                    sizeBytes = 70_913_104L,
                    category = DistroCategory.GENERAL,
                    description = "Arch Linux ARM 滚动更新",
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
                    description = "Void Linux aarch64",
                    packageManager = "xbps",
                    recommendedCpuCores = 1,
                    recommendedMemoryMb = 1024,
                    recommendedDiskGb = 8
                ),
                DistroInfo(
                    id = "kali_nethunter_minimal",
                    name = "Kali Linux",
                    version = "NetHunter minimal",
                    architecture = "aarch64",
                    downloadUrl = "https://kali.download/nethunter-images/current/rootfs/kalifs-arm64-minimal.tar.xz",
                    mirrorUrls = listOf(
                        "https://mirrors.ustc.edu.cn/kali-images/nethunter-images/current/rootfs/kalifs-arm64-minimal.tar.xz"
                    ),
                    sizeBytes = 180_000_000L,
                    checksumSha256 = KALI_MINIMAL_ARM64_SHA256,
                    category = DistroCategory.SECURITY,
                    description = "Kali Linux NetHunter minimal，Haven 式 PRoot rootfs",
                    packageManager = "apt",
                    recommendedCpuCores = 2,
                    recommendedMemoryMb = 2048,
                    recommendedDiskGb = 16
                ),
                DistroInfo(
                    id = "rocky_linux",
                    name = "Rocky Linux",
                    version = "latest",
                    architecture = "aarch64",
                    sizeBytes = 1L,
                    category = DistroCategory.SERVER,
                    description = "Rocky Linux 通过用户镜像导入",
                    packageManager = "dnf",
                    unavailableReason = ROCKY_IMPORT
                ),
                DistroInfo(
                    id = "windows",
                    name = "Windows",
                    version = "user image",
                    architecture = "aarch64",
                    sizeBytes = 1L,
                    category = DistroCategory.DESKTOP,
                    description = "Windows 评估版/正式版由用户提供镜像",
                    packageManager = "",
                    unavailableReason = WINDOWS_IMPORT
                ),
                DistroInfo(
                    id = "debian_12_x86_64",
                    name = "Debian",
                    version = "12",
                    architecture = "x86_64",
                    sizeBytes = 110_000_000L,
                    category = DistroCategory.SERVER,
                    description = "Debian 12 x86_64 模拟",
                    packageManager = "apt",
                    emulatedX86 = true,
                    unavailableReason = x86Reason
                ),
                DistroInfo(
                    id = "alpine_3_21_x86_64",
                    name = "Alpine Linux",
                    version = "3.21",
                    architecture = "x86_64",
                    sizeBytes = 5_000_000L,
                    category = DistroCategory.LIGHTWEIGHT,
                    description = "Alpine 3.21 x86_64 模拟",
                    packageManager = "apk",
                    emulatedX86 = true,
                    unavailableReason = x86Reason
                ),
                DistroInfo(
                    id = "debian_13_x86_64",
                    name = "Debian",
                    version = "13",
                    architecture = "x86_64",
                    sizeBytes = 115_000_000L,
                    category = DistroCategory.SERVER,
                    description = "Debian 13 x86_64 模拟",
                    packageManager = "apt",
                    emulatedX86 = true,
                    unavailableReason = x86Reason
                ),
                DistroInfo(
                    id = "ubuntu_24_04_x86_64",
                    name = "Ubuntu",
                    version = "24.04 LTS",
                    architecture = "x86_64",
                    sizeBytes = 35_000_000L,
                    category = DistroCategory.SERVER,
                    description = "Ubuntu 24.04 LTS x86_64 模拟",
                    packageManager = "apt",
                    emulatedX86 = true,
                    unavailableReason = x86Reason
                ),
                DistroInfo(
                    id = "archlinux_x86_64",
                    name = "Arch Linux",
                    version = "latest",
                    architecture = "x86_64",
                    sizeBytes = 80_000_000L,
                    category = DistroCategory.GENERAL,
                    description = "Arch Linux x86_64 模拟",
                    packageManager = "pacman",
                    emulatedX86 = true,
                    unavailableReason = x86Reason
                ),
                DistroInfo(
                    id = "voidlinux_x86_64",
                    name = "Void Linux",
                    version = "20250202",
                    architecture = "x86_64",
                    sizeBytes = 4_000_000L,
                    category = DistroCategory.LIGHTWEIGHT,
                    description = "Void Linux x86_64 模拟",
                    packageManager = "xbps",
                    emulatedX86 = true,
                    unavailableReason = x86Reason
                )
            )
        }
    }

    private fun catalog(): List<DistroInfo> = buildCatalog(emulationReady = false)
}

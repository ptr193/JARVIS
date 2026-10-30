package com.jarvis.pineapple.vm

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * 支持的镜像格式
 */
enum class ImageFormat(
    val extension: String,
    val description: String,
    val isArchive: Boolean = false
) {
    TARGZ("tar.gz", "压缩的 rootfs 归档 (tar.gz)", isArchive = true),
    TARXZ("tar.xz", "压缩的 rootfs 归档 (tar.xz)", isArchive = true),
    TAR("tar", "未压缩的 rootfs 归档 (tar)", isArchive = true),
    OVA("ova", "Open Virtualization Archive", isArchive = true),
    OVF("ovf", "Open Virtualization Format"),
    QCOW2("qcow2", "QEMU Copy-On-Write v2"),
    VMDK("vmdk", "VMware Virtual Disk"),
    VHD("vhd", "Microsoft Virtual Hard Disk"),
    VHDX("vhdx", "Microsoft Virtual Hard Disk v2"),
    RAW("raw", "Raw Disk Image"),
    ISO("iso", "ISO 9660 Disk Image")
}

/**
 * 镜像元信息
 */
data class ImageMetadata(
    val format: ImageFormat,
    val sizeBytes: Long,
    val systemType: String = "Unknown",
    val architecture: String = "aarch64",
    val bootMode: String = "N/A",
    val diskSizeGb: Float = 0f,
    val isValid: Boolean = true,
    val errorMessage: String = ""
)

/**
 * 导入进度
 */
data class ImportProgress(
    val stage: ImportStage,
    val progress: Float = 0f,
    val message: String = ""
)

enum class ImportStage {
    IDLE, VALIDATING, ANALYZING, EXTRACTING, REGISTERING, COMPLETED, FAILED
}

/**
 * 导入结果
 */
sealed class ImportResult {
    data class Success(val vmId: String, val message: String) : ImportResult()
    data class Failure(val error: String) : ImportResult()
}

/**
 * 虚拟机镜像导入器（REQ-03）。
 *
 * 通过魔数识别镜像真实格式；对 tar 系列 rootfs 归档执行真实解压到应用私有目录，
 * 对磁盘镜像（qcow2/vmdk/iso 等）登记元信息并标注需外部引擎（如 QEMU）引导。
 */
class VmImageImporter(private val context: Context) {

    private val _importProgress = MutableStateFlow(ImportProgress(ImportStage.IDLE))
    val importProgress: StateFlow<ImportProgress> = _importProgress.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importedImages = MutableStateFlow<List<ImportedImage>>(emptyList())
    val importedImages: StateFlow<List<ImportedImage>> = _importedImages.asStateFlow()

    private val rootfsRoot = File(context.filesDir, "rootfs")

    private fun deviceArchitecture(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.startsWith("arm64") || abi == "aarch64" -> "aarch64"
            abi.startsWith("armeabi") -> "arm"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> abi
        }
    }

    fun detectFormat(filePath: String): ImageFormat? {
        val name = filePath.substringAfterLast("/").lowercase()
        return when {
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> ImageFormat.TARGZ
            name.endsWith(".tar.xz") || name.endsWith(".txz") -> ImageFormat.TARXZ
            name.endsWith(".tar") -> ImageFormat.TAR
            name.endsWith(".ova") -> ImageFormat.OVA
            else -> {
                val extension = name.substringAfterLast(".", "")
                ImageFormat.values().find { it.extension == extension }
            }
        }
    }

    private fun readMagic(file: File, offset: Long = 0, length: Int = 8): ByteArray {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(length)
                val read = raf.read(buf)
                if (read <= 0) ByteArray(0) else buf.copyOf(read)
            }
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    /**
     * 读取前若干字节并识别真实格式。
     */
    private fun sniffFormat(file: File): ImageFormat? {
        val head = readMagic(file, 0, 8)
        if (head.size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) return ImageFormat.TARGZ
        if (head.size >= 6 && head[0] == 0xFD.toByte() && head[1] == 0x37.toByte() &&
            head[2] == 0x7A.toByte() && head[3] == 0x58.toByte()) return ImageFormat.TARXZ
        if (head.size >= 4) {
            val tag = String(head.copyOf(4), Charsets.US_ASCII)
            if (tag == "QFI\u00fb") return ImageFormat.QCOW2
            if (tag == "KDMV") return ImageFormat.VMDK
        }
        if (head.size >= 8) {
            val tag8 = String(head.copyOf(8), Charsets.US_ASCII)
            if (tag8 == "conectix") return ImageFormat.VHD
            if (tag8 == "vhdxfile") return ImageFormat.VHDX
        }
        // tar 的 "ustar" 位于偏移 257
        val tarMagic = readMagic(file, 257, 5)
        if (tarMagic.size >= 5 && String(tarMagic, Charsets.US_ASCII) == "ustar") return ImageFormat.TAR
        // ISO 的 "CD001" 位于偏移 0x8001
        val isoMagic = readMagic(file, 0x8001, 5)
        if (isoMagic.size >= 5 && String(isoMagic, Charsets.US_ASCII) == "CD001") return ImageFormat.ISO
        return null
    }

    fun analyzeImage(filePath: String): ImageMetadata {
        val file = File(filePath)
        if (!file.exists()) {
            return ImageMetadata(
                format = ImageFormat.RAW,
                sizeBytes = 0,
                isValid = false,
                errorMessage = "文件不存在: $filePath"
            )
        }

        val format = sniffFormat(file) ?: detectFormat(filePath) ?: return ImageMetadata(
            format = ImageFormat.RAW,
            sizeBytes = file.length(),
            isValid = false,
            errorMessage = "不支持的镜像格式: ${filePath.substringAfterLast(".")}"
        )

        return ImageMetadata(
            format = format,
            sizeBytes = file.length(),
            systemType = detectSystemType(format),
            architecture = deviceArchitecture(),
            bootMode = "N/A",
            diskSizeGb = file.length() / (1024f * 1024 * 1024),
            isValid = true
        )
    }

    suspend fun importImage(filePath: String, vmName: String = ""): ImportResult {
        if (_isImporting.value) {
            return ImportResult.Failure("已有导入任务正在进行中")
        }

        _isImporting.value = true

        try {
            updateProgress(ImportStage.VALIDATING, 0.1f, "正在验证镜像文件...")
            val file = File(filePath)
            if (!file.exists()) {
                _isImporting.value = false
                return ImportResult.Failure("文件不存在: $filePath")
            }

            updateProgress(ImportStage.ANALYZING, 0.3f, "正在分析镜像元信息...")
            val metadata = analyzeImage(filePath)
            if (!metadata.isValid) {
                _isImporting.value = false
                return ImportResult.Failure(metadata.errorMessage)
            }

            var extractedRootfs = ""
            if (metadata.format.isArchive) {
                updateProgress(ImportStage.EXTRACTING, 0.5f, "正在解压 rootfs 归档...")
                val target = File(rootfsRoot, "imported_${System.currentTimeMillis()}")
                val ok = withContext(Dispatchers.IO) {
                    try {
                        if (target.exists()) target.deleteRecursively()
                        target.mkdirs()
                        TarExtractor.extract(file, target)
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                if (!ok) {
                    _isImporting.value = false
                    return ImportResult.Failure("解压镜像失败")
                }
                extractedRootfs = target.absolutePath
            }

            updateProgress(ImportStage.REGISTERING, 0.85f, "正在注册到虚拟机库...")
            val importedImage = ImportedImage(
                id = "img_${System.currentTimeMillis()}",
                name = vmName.ifEmpty { file.nameWithoutExtension },
                originalPath = filePath,
                format = metadata.format,
                sizeBytes = metadata.sizeBytes,
                systemType = metadata.systemType,
                architecture = metadata.architecture,
                rootfsPath = extractedRootfs,
                importedAt = System.currentTimeMillis()
            )
            _importedImages.value = _importedImages.value + importedImage

            val message = if (metadata.format.isArchive) {
                "镜像 '${importedImage.name}' 已导入并解压为可用 rootfs"
            } else {
                "镜像 '${importedImage.name}' 已登记（磁盘镜像需外部引擎引导）"
            }
            updateProgress(ImportStage.COMPLETED, 1f, message)
            _isImporting.value = false
            return ImportResult.Success(importedImage.id, message)

        } catch (e: Exception) {
            updateProgress(ImportStage.FAILED, 0f, "导入失败: ${e.message}")
            _isImporting.value = false
            return ImportResult.Failure("导入失败: ${e.message}")
        }
    }

    fun deleteImage(imageId: String): Boolean {
        val image = _importedImages.value.firstOrNull { it.id == imageId } ?: return false
        if (image.rootfsPath.isNotBlank()) {
            try { File(image.rootfsPath).deleteRecursively() } catch (_: Exception) {}
        }
        _importedImages.value = _importedImages.value.filterNot { it.id == imageId }
        return true
    }

    fun getImportedImages(): List<ImportedImage> = _importedImages.value

    fun isSupportedFormat(filePath: String): Boolean = detectFormat(filePath) != null

    fun getSupportedFormats(): List<String> =
        ImageFormat.values().map { "${it.extension} (${it.description})" }

    private fun updateProgress(stage: ImportStage, progress: Float, message: String) {
        _importProgress.value = ImportProgress(stage, progress, message)
    }

    private fun detectSystemType(format: ImageFormat): String {
        return when (format) {
            ImageFormat.ISO -> "Linux Installer"
            ImageFormat.OVA, ImageFormat.OVF -> "Virtual Appliance"
            ImageFormat.TARGZ, ImageFormat.TARXZ, ImageFormat.TAR -> "Linux rootfs"
            else -> "Linux"
        }
    }
}

/**
 * 已导入的镜像
 */
data class ImportedImage(
    val id: String,
    val name: String,
    val originalPath: String,
    val format: ImageFormat,
    val sizeBytes: Long,
    val systemType: String,
    val architecture: String,
    val rootfsPath: String = "",
    val importedAt: Long
)

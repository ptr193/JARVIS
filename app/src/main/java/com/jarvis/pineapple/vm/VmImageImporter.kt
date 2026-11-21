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

/** 可识别的镜像格式；只有 rootfs tar 归档能够由 PRoot 直接导入。 */
enum class ImageFormat(
    val extension: String,
    val description: String,
    val isRootfsArchive: Boolean = false
) {
    TARGZ("tar.gz", "压缩的 rootfs 归档 (tar.gz)", isRootfsArchive = true),
    TARXZ("tar.xz", "压缩的 rootfs 归档 (tar.xz)", isRootfsArchive = true),
    TAR("tar", "未压缩的 rootfs 归档 (tar)", isRootfsArchive = true),
    OVA("ova", "Open Virtualization Archive"),
    OVF("ovf", "Open Virtualization Format"),
    QCOW2("qcow2", "QEMU Copy-On-Write v2"),
    VMDK("vmdk", "VMware Virtual Disk"),
    VHD("vhd", "Microsoft Virtual Hard Disk"),
    VHDX("vhdx", "Microsoft Virtual Hard Disk v2"),
    RAW("raw", "Raw Disk Image"),
    ISO("iso", "ISO 9660 Disk Image")
}

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

data class ImportProgress(
    val stage: ImportStage,
    val progress: Float = 0f,
    val message: String = ""
)

enum class ImportStage {
    IDLE, VALIDATING, ANALYZING, EXTRACTING, REGISTERING, COMPLETED, FAILED
}

sealed class ImportResult {
    data class Success(val vmId: String, val message: String) : ImportResult()
    data class Failure(val error: String) : ImportResult()
}

/**
 * 将本地 Linux rootfs tar 归档解压并创建真实 PRoot 虚拟机。
 * OVA、ISO、qcow2 等磁盘镜像需要虚拟化引擎，不会被伪装成可启动 rootfs。
 */
class VmImageImporter(
    context: Context,
    private val vmManager: HavenVmManager
) {
    private val appContext = context.applicationContext
    private val _importProgress = MutableStateFlow(ImportProgress(ImportStage.IDLE))
    val importProgress: StateFlow<ImportProgress> = _importProgress.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val rootfsRoot = File(appContext.filesDir, "rootfs")

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

    private fun readMagic(file: File, offset: Long = 0, length: Int = 8): ByteArray = try {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(length)
            val read = raf.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    } catch (_: Exception) {
        ByteArray(0)
    }

    private fun sniffFormat(file: File): ImageFormat? {
        val head = readMagic(file)
        if (head.size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) {
            return ImageFormat.TARGZ
        }
        if (head.size >= 6 && head[0] == 0xFD.toByte() && head[1] == 0x37.toByte() &&
            head[2] == 0x7A.toByte() && head[3] == 0x58.toByte()
        ) {
            return ImageFormat.TARXZ
        }
        if (head.size >= 4) {
            when (String(head.copyOf(4), Charsets.US_ASCII)) {
                "QFIû" -> return ImageFormat.QCOW2
                "KDMV" -> return ImageFormat.VMDK
            }
        }
        if (head.size >= 8) {
            when (String(head, Charsets.US_ASCII)) {
                "conectix" -> return ImageFormat.VHD
                "vhdxfile" -> return ImageFormat.VHDX
            }
        }
        val tarMagic = readMagic(file, 257, 5)
        if (tarMagic.size >= 5 && String(tarMagic, Charsets.US_ASCII) == "ustar") {
            return ImageFormat.TAR
        }
        val isoMagic = readMagic(file, 0x8001, 5)
        if (isoMagic.size >= 5 && String(isoMagic, Charsets.US_ASCII) == "CD001") {
            return ImageFormat.ISO
        }
        return null
    }

    fun analyzeImage(filePath: String): ImageMetadata {
        val file = File(filePath)
        if (!file.isFile) {
            return ImageMetadata(
                format = ImageFormat.RAW,
                sizeBytes = 0,
                isValid = false,
                errorMessage = "文件不存在或不是普通文件: $filePath"
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
            systemType = if (format.isRootfsArchive) "Linux rootfs" else "Virtual Disk",
            architecture = deviceArchitecture(),
            diskSizeGb = file.length() / (1024f * 1024 * 1024)
        )
    }

    suspend fun importImage(filePath: String, vmName: String = ""): ImportResult {
        if (_isImporting.value) return ImportResult.Failure("已有导入任务正在进行中")
        _isImporting.value = true
        return try {
            updateProgress(ImportStage.VALIDATING, 0.1f, "正在验证镜像文件...")
            val metadata = analyzeImage(filePath)
            if (!metadata.isValid) return fail(metadata.errorMessage)
            if (!metadata.format.isRootfsArchive) {
                return fail("仅支持 Linux rootfs 的 tar、tar.gz、tar.xz 归档；磁盘镜像需要 QEMU 等外部引擎")
            }

            updateProgress(ImportStage.ANALYZING, 0.3f, "正在分析 rootfs 归档...")
            val source = File(filePath)
            val target = File(rootfsRoot, "imported_${System.currentTimeMillis()}")
            updateProgress(ImportStage.EXTRACTING, 0.5f, "正在解压 rootfs 归档...")
            try {
                withContext(Dispatchers.IO) {
                    if (target.exists()) target.deleteRecursively()
                    if (!target.mkdirs()) throw IllegalStateException("无法创建导入目录")
                    TarExtractor.extract(source, target)
                }
            } catch (e: Exception) {
                target.deleteRecursively()
                return fail("解压 rootfs 失败: ${e.message}")
            }

            updateProgress(ImportStage.REGISTERING, 0.85f, "正在创建虚拟机...")
            val name = vmName.ifBlank { source.name.substringBefore(".tar").substringBeforeLast('.') }
            val created = vmManager.createImportedVm(name, target.absolutePath)
            val vm = created.vm
            if (vm == null) {
                target.deleteRecursively()
                return fail(created.error ?: "创建虚拟机失败")
            }
            val message = "镜像已导入，虚拟机 '${vm.name}' 已创建"
            updateProgress(ImportStage.COMPLETED, 1f, message)
            ImportResult.Success(vm.id, message)
        } catch (e: Exception) {
            fail("导入失败: ${e.message}")
        } finally {
            _isImporting.value = false
        }
    }

    fun isSupportedFormat(filePath: String): Boolean =
        analyzeImage(filePath).let { it.isValid && it.format.isRootfsArchive }

    fun getSupportedFormats(): List<String> = ImageFormat.values()
        .filter { it.isRootfsArchive }
        .map { "${it.extension} (${it.description})" }

    private fun fail(message: String): ImportResult.Failure {
        updateProgress(ImportStage.FAILED, 0f, message)
        return ImportResult.Failure(message)
    }

    private fun updateProgress(stage: ImportStage, progress: Float, message: String) {
        _importProgress.value = ImportProgress(stage, progress, message)
    }
}

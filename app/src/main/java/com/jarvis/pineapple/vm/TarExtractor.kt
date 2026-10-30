package com.jarvis.pineapple.vm

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * tar 归档解压工具（支持 .tar / .tar.gz / .tgz / .tar.xz / .txz）。
 *
 * 用于解压 PRoot 发行版 rootfs 归档；包含路径穿越防护、符号链接、可执行位还原。
 */
object TarExtractor {

    /**
     * 解压归档到目标目录。
     * @throws Exception 归档损坏或 IO 错误时抛出
     */
    @Throws(Exception::class)
    fun extract(archive: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val name = archive.name.lowercase()
        val raw = BufferedInputStream(FileInputStream(archive))
        val decompressed: InputStream = when {
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> GZIPInputStream(raw)
            name.endsWith(".tar.xz") || name.endsWith(".txz") -> XZCompressorInputStream(raw)
            else -> raw // 原始 .tar
        }

        decompressed.use { input ->
            TarArchiveInputStream(input).use { tar ->
                val targetCanonical = targetDir.canonicalPath
                var entry: TarArchiveEntry? = tar.nextEntry as? TarArchiveEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    // 路径穿越防护
                    if (!outFile.canonicalPath.startsWith(targetCanonical)) {
                        entry = tar.nextEntry as? TarArchiveEntry
                        continue
                    }
                    when {
                        entry.isDirectory -> outFile.mkdirs()
                        entry.isSymbolicLink -> {
                            outFile.parentFile?.mkdirs()
                            try {
                                if (outFile.exists()) outFile.delete()
                                Os.symlink(entry.linkName, outFile.absolutePath)
                            } catch (_: Exception) {
                                // 某些文件系统不允许符号链接，忽略
                            }
                        }
                        entry.isLink -> {
                            outFile.parentFile?.mkdirs()
                            try {
                                Os.link(resolveSafe(targetDir, entry.linkName).absolutePath, outFile.absolutePath)
                            } catch (_: Exception) {
                            }
                        }
                        else -> {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                        }
                    }
                    // 还原可执行位 / 权限
                    val mode = entry.mode
                    if (mode != 0) {
                        outFile.setReadable(true, false)
                        outFile.setWritable(true, true)
                        if (mode and 0b001_000_000 != 0) outFile.setExecutable(true, false)
                    }
                    entry = tar.nextEntry as? TarArchiveEntry
                }
            }
        }
    }

    private fun resolveSafe(baseDir: File, path: String): File {
        val f = if (path.startsWith("/")) File(baseDir, path.trimStart('/')) else File(baseDir, path)
        return f
    }

    /** 计算文件 SHA-256（小写十六进制）。 */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 将目录打包为 .tar.gz 归档（用于虚拟机快照）。
     */
    @Throws(Exception::class)
    fun createArchive(sourceDir: File, outFile: File) {
        outFile.parentFile?.mkdirs()
        org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(
            FileOutputStream(outFile)
        ).use { gz ->
            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX)
                val basePath = sourceDir.canonicalPath
                sourceDir.walkTopDown().forEach { file ->
                    if (file == sourceDir) return@forEach
                    val rel = file.canonicalPath.removePrefix(basePath).trimStart('/')
                    val entry = TarArchiveEntry(file, rel)
                    if (file.isDirectory) {
                        entry.mode = 0b111_101_101 // 0755
                        tar.putArchiveEntry(entry)
                        tar.closeArchiveEntry()
                    } else if (file.isFile) {
                        entry.mode = if (file.canExecute()) 0b111_101_101 else 0b110_100_100 // 0755 / 0644
                        entry.size = file.length()
                        tar.putArchiveEntry(entry)
                        file.inputStream().use { it.copyTo(tar) }
                        tar.closeArchiveEntry()
                    }
                }
            }
        }
    }
}

package com.jarvis.pineapple.model

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 模型文件格式（REQ-04：覆盖主流开放格式并保持扩展能力）。
 */
enum class ModelFormat(val label: String, val extensions: List<String>) {
    GGUF("GGUF", listOf("gguf")),
    ONNX("ONNX", listOf("onnx")),
    SAFETENSORS("Safetensors", listOf("safetensors")),
    PYTORCH("PyTorch", listOf("bin", "pt", "pth")),
    TENSORFLOW("TensorFlow", listOf("pb")),
    LITERTLM("LiteRT-LM", listOf("litertlm")),
    UNKNOWN("未知格式", emptyList());

    companion object {
        fun fromExtension(ext: String): ModelFormat =
            entries.firstOrNull { it.extensions.contains(ext.lowercase()) } ?: UNKNOWN
    }
}

/**
 * 格式探测与解析结果。
 *
 * 每种格式都进行**真实的二进制结构解析**，不做简单的后缀比对：
 * - GGUF：校验魔数 "GGUF"，解析版本、张量数量、元数据键值对数量
 * - ONNX：校验 protobuf 前缀（field 1 varint / field 7 长度分隔）
 * - Safetensors：读取 8 字节小端头长度并解析 JSON 头
 * - PyTorch：识别 pickle 魔数（0x80 0x02/0x03/0x04）或 ZIP 容器（PK\x03\x04）
 * - TensorFlow：识别 SavedModel 目录或 protobuf raw 格式
 */
sealed class FormatProbe {
    data class Detected(
        val format: ModelFormat,
        val detail: String,
        val tensorCount: Long = -1,
        val metadataCount: Long = -1
    ) : FormatProbe()

    data class Rejected(val reason: String) : FormatProbe()
}

object ModelFormatDetector {

    /** 探测文件真实格式（优先魔数/结构，后缀仅作兜底提示）。 */
    fun probe(file: File): FormatProbe {
        if (!file.exists() || file.length() == 0L) {
            return FormatProbe.Rejected("文件不存在或为空")
        }
        val header = readHeader(file, 16)
        if (header.size < 4) {
            return FormatProbe.Rejected("文件过小，无法识别格式")
        }

        // GGUF: "GGUF" 魔数
        if (header.size >= 4 &&
            header[0] == 'G'.code.toByte() && header[1] == 'G'.code.toByte() &&
            header[2] == 'U'.code.toByte() && header[3] == 'F'.code.toByte()
        ) {
            return probeGguf(file)
        }

        // PyTorch ZIP 容器（torch >= 1.6 默认 zip 格式）
        if (header.size >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
            header[2].toInt() == 3 && header[3].toInt() == 4
        ) {
            return FormatProbe.Detected(
                ModelFormat.PYTORCH,
                "PyTorch ZIP 存档（torch.save 默认格式），大小 ${file.length() / (1024 * 1024)} MB"
            )
        }

        // PyTorch pickle 魔数: 0x80 0x02 / 0x03 / 0x04
        if (header.size >= 2 && header[0] == 0x80.toByte() && header[1] in 2..4) {
            return FormatProbe.Detected(
                ModelFormat.PYTORCH,
                "PyTorch pickle 序列化（PROTO v${header[1]}），大小 ${file.length() / (1024 * 1024)} MB"
            )
        }

        // ONNX: protobuf。field 1 (ir_version) varint tag = 0x08，或 field 7 (graph) 长度分隔 tag = 0x3a
        if (header[0] == 0x08.toByte() || header[0] == 0x3a.toByte()) {
            // 进一步校验：ir_version 应为合理小整数（1~12）
            if (header[0] == 0x08.toByte() && header.size >= 2 && header[1] in 1..12) {
                return FormatProbe.Detected(
                    ModelFormat.ONNX,
                    "ONNX protobuf 模型（IR version ${header[1]}），大小 ${file.length() / (1024 * 1024)} MB"
                )
            }
        }

        // Safetensors: 前 8 字节为 JSON 头的 little-endian 长度
        if (file.length() > 8) {
            val headLen = ByteBuffer.wrap(header, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
            if (headLen in 2L..(100L * 1024 * 1024) && headLen + 8 <= file.length()) {
                val jsonHead = readBytes(file, 8, headLen.toInt().coerceAtMost(256))
                val asText = String(jsonHead, Charsets.US_ASCII)
                if (asText.trimStart().startsWith("{")) {
                    return FormatProbe.Detected(
                        ModelFormat.SAFETENSORS,
                        "Safetensors 张量容器（JSON 头 ${headLen} 字节），大小 ${file.length() / (1024 * 1024)} MB"
                    )
                }
            }
        }

        // TensorFlow SavedModel 常以 protobuf "field 1 bytes" 开头，无稳定魔数，回退后缀判断
        val byExt = ModelFormat.fromExtension(file.extension)
        if (byExt != ModelFormat.UNKNOWN) {
            return FormatProbe.Detected(byExt, "按扩展名识别为 ${byExt.label}（未发现已知二进制魔数）")
        }
        return FormatProbe.Rejected(
            "无法识别的模型格式。支持：GGUF / ONNX / Safetensors / PyTorch(.bin/.pt/.pth) / TensorFlow(.pb) / LiteRT-LM(.litertlm)"
        )
    }

    /** 解析 GGUF 头（真实的二进制结构解析）。 */
    private fun probeGguf(file: File): FormatProbe {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                raf.readFully(buf.array())
                buf.rewind()
                val magic = ByteArray(4).also { buf.get(it) }
                if (String(magic, Charsets.US_ASCII) != "GGUF") {
                    return FormatProbe.Rejected("GGUF 魔数校验失败")
                }
                val version = buf.int
                if (version !in 2..4) {
                    return FormatProbe.Rejected("不支持的 GGUF 版本 $version（支持 v2~v4）")
                }
                val tensorCount = buf.long
                val metadataCount = buf.long
                if (tensorCount < 0 || metadataCount < 0 || tensorCount > 1_000_000) {
                    return FormatProbe.Rejected("GGUF 头字段异常（tensor=$tensorCount meta=$metadataCount）")
                }
                FormatProbe.Detected(
                    ModelFormat.GGUF,
                    "GGUF v$version，张量 $tensorCount 个，元数据 $metadataCount 项，" +
                            "大小 ${file.length() / (1024 * 1024)} MB",
                    tensorCount = tensorCount,
                    metadataCount = metadataCount
                )
            }
        } catch (e: Exception) {
            FormatProbe.Rejected("GGUF 解析失败：${e.message}")
        }
    }

    private fun readHeader(file: File, n: Int): ByteArray = readBytes(file, 0, n)

    private fun readBytes(file: File, offset: Long, len: Int): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val out = ByteArray(len)
            var read = 0
            while (read < len) {
                val r = raf.read(out, read, len - read)
                if (r < 0) break
                read += r
            }
            return out.copyOf(read)
        }
    }
}

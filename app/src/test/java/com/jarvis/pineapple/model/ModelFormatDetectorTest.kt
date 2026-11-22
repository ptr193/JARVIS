package com.jarvis.pineapple.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 模型格式探测器测试（REQ-04：真实二进制解析，非后缀比对）。
 */
class ModelFormatDetectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(name: String, bytes: ByteArray): File =
        tmp.newFile(name).also { it.writeBytes(bytes) }

    @Test
    fun `gguf magic detected`() {
        // GGUF v3 header: magic(4) + version(4) + tensorCount(8) + metadataCount(8)
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("GGUF".toByteArray())
        buf.putInt(3)
        buf.putLong(12L)
        buf.putLong(5L)
        val f = write("m.gguf", buf.array())
        val probe = ModelFormatDetector.probe(f)
        assertTrue(probe is FormatProbe.Detected)
        probe as FormatProbe.Detected
        assertEquals(ModelFormat.GGUF, probe.format)
        assertEquals(12L, probe.tensorCount)
        assertEquals(5L, probe.metadataCount)
    }

    @Test
    fun `onnx protobuf header detected`() {
        // field 1 varint tag 0x08, ir_version 9
        val f = write("m.onnx", byteArrayOf(0x08, 0x09, 0x12, 0x07))
        val probe = ModelFormatDetector.probe(f)
        assertTrue(probe is FormatProbe.Detected)
        assertEquals(ModelFormat.ONNX, (probe as FormatProbe.Detected).format)
    }

    @Test
    fun `pytorch pickle magic detected`() {
        val f = write("m.pt", byteArrayOf(0x80.toByte(), 0x02, '}'.code.toByte(), '.'.code.toByte()))
        val probe = ModelFormatDetector.probe(f)
        assertTrue(probe is FormatProbe.Detected)
        assertEquals(ModelFormat.PYTORCH, (probe as FormatProbe.Detected).format)
    }

    @Test
    fun `pytorch zip container detected`() {
        val f = tmp.newFile("m.pth")
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("archive/data.pkl"))
            zip.write(byteArrayOf(0x80.toByte(), 0x02, '}'.code.toByte(), '.'.code.toByte()))
            zip.closeEntry()
        }
        val probe = ModelFormatDetector.probe(f)
        assertTrue(probe is FormatProbe.Detected)
        assertEquals(ModelFormat.PYTORCH, (probe as FormatProbe.Detected).format)
    }

    @Test
    fun `safetensors json head detected`() {
        val json = """{"__metadata__":{"format":"pt"},"w":{"dtype":"F32","shape":[2,2],"data_offsets":[0,16]}}"""
        val jsonBytes = json.toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(8 + jsonBytes.size + 16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(jsonBytes.size.toLong())
        buf.put(jsonBytes)
        buf.put(ByteArray(16))
        val f = write("m.safetensors", buf.array())
        val probe = ModelFormatDetector.probe(f)
        assertTrue(probe is FormatProbe.Detected)
        assertEquals(ModelFormat.SAFETENSORS, (probe as FormatProbe.Detected).format)
    }

    @Test
    fun `empty file rejected`() {
        val f = File(tmp.root, "empty.bin")
        f.createNewFile()
        val probe = ModelFormatDetector.probe(f)
        assertTrue(probe is FormatProbe.Rejected)
    }

    @Test
    fun `random bytes rejected or extension fallback`() {
        // 无已知魔数 + 未知扩展名 → Rejected
        val f = write("m.xyz", ByteArray(32) { (it * 7 + 3).toByte() })
        val probe = ModelFormatDetector.probe(f)
        assertTrue(probe is FormatProbe.Rejected)
    }

    @Test
    fun `known extension cannot replace binary validation`() {
        val f = write("weights.bin", ByteArray(64) { 0x11 })
        assertTrue(ModelFormatDetector.probe(f) is FormatProbe.Rejected)
    }

    @Test
    fun `safetensors tensor range outside payload rejected`() {
        val json = """{"w":{"dtype":"F32","shape":[2,2],"data_offsets":[0,32]}}"""
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(8 + jsonBytes.size + 16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(jsonBytes.size.toLong())
        buf.put(jsonBytes)
        buf.put(ByteArray(16))
        assertTrue(ModelFormatDetector.probe(write("bad.safetensors", buf.array())) is FormatProbe.Rejected)
    }

    @Test
    fun `source and copied target sha256 detect tampering`() {
        val source = write("source.gguf", ByteArray(256) { it.toByte() })
        val target = File(tmp.root, "target.gguf")
        source.copyTo(target)
        assertEquals(ModelImportManager.sha256Of(source), ModelImportManager.sha256Of(target))
        target.appendBytes(byteArrayOf(1))
        assertTrue(ModelImportManager.sha256Of(source) != ModelImportManager.sha256Of(target))
    }

    @Test
    fun `litertlm extension is only an engine validation candidate`() {
        val f = write("candidate.litertlm", ByteArray(64) { 0x11 })
        val probe = ModelFormatDetector.probe(f)
        assertTrue(probe is FormatProbe.Detected)
        assertTrue((probe as FormatProbe.Detected).detail.contains("待真实引擎"))
    }
}

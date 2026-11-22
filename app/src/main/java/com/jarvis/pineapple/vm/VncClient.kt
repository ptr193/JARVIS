package com.jarvis.pineapple.vm

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 轻量 RFB（VNC）客户端，用于在应用内显示并操作虚拟机桌面。
 *
 * 仅实现桌面场景必需的最小协议：RFB 3.3/3.7/3.8 握手、None 安全类型、
 * 32bpp true-color、RAW 编码、指针/键盘事件。服务器端（x11vnc）以 `-nopw` 启动。
 * 不支持 Tight/ZRLE 等压缩编码，也不处理文件传输。
 */
class VncClient(private val listener: Listener) {

    interface Listener {
        fun onConnected(width: Int, height: Int)
        fun onFrame(pixels: IntArray, width: Int, height: Int)
        fun onDisconnected(reason: String)
    }

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var output: DataOutputStream? = null

    @Volatile
    private var running = false

    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    /** 连接并持续接收画面，直到断开或 [disconnect]。 */
    suspend fun run(host: String, port: Int) = withContext(Dispatchers.IO) {
        var reason = "连接已关闭"
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 8_000)
            socket = s
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            output = out
            running = true

            val serverVersion = readVersion(input)
            writeVersion(out, serverVersion)
            negotiateSecurity(input, out, serverVersion)
            writeClientInit(out)
            readServerInit(input)

            setPixelFormat(out)
            setEncodings(out)
            requestFrame(out, incremental = false)

            val pixels = IntArray(frameWidth * frameHeight)
            while (running) {
                val messageType = input.readUnsignedByte()
                when (messageType) {
                    0 -> {
                        input.skipFully(1)
                        val rects = input.readUnsignedShort()
                        var changed = false
                        repeat(rects) {
                            val x = input.readUnsignedShort()
                            val y = input.readUnsignedShort()
                            val w = input.readUnsignedShort()
                            val h = input.readUnsignedShort()
                            val encoding = input.readInt()
                            if (encoding != 0) {
                                throw IllegalStateException("服务器使用了不支持的编码：$encoding")
                            }
                            readRawRect(input, pixels, x, y, w, h)
                            changed = true
                        }
                        if (changed) listener.onFrame(pixels, frameWidth, frameHeight)
                        requestFrame(out, incremental = true)
                    }
                    2 -> Unit // Bell
                    3 -> {
                        input.skipFully(3)
                        val length = input.readInt()
                        input.skipFully(length)
                    }
                    else -> throw IllegalStateException("未知的 RFB 消息类型：$messageType")
                }
            }
            reason = "连接已关闭"
        } catch (e: Exception) {
            reason = if (running) "VNC 连接中断：${e.message ?: e.javaClass.simpleName}" else "连接已关闭"
        } finally {
            running = false
            runCatching { output?.close() }
            runCatching { socket?.close() }
            output = null
            socket = null
            if (reason.isNotBlank()) listener.onDisconnected(reason)
        }
    }

    fun disconnect() {
        running = false
        runCatching { socket?.close() }
    }

    /** 指针事件：buttonMask 1=左键 2=中键 4=右键，0=释放。 */
    fun pointer(x: Int, y: Int, buttonMask: Int) {
        if (x < 0 || y < 0 || x >= frameWidth || y >= frameHeight) return
        synchronized(this) {
            val out = output ?: return
            runCatching {
                out.writeByte(5)
                out.writeByte(buttonMask)
                out.writeShort(x)
                out.writeShort(y)
                out.flush()
            }
        }
    }

    /** 按键事件（X11 keysym）。 */
    fun key(keysym: Int, down: Boolean) {
        synchronized(this) {
            val out = output ?: return
            runCatching {
                out.writeByte(4)
                out.writeByte(if (down) 1 else 0)
                out.writeShort(0)
                out.writeInt(keysym)
                out.flush()
            }
        }
    }

    /** 发送文本：ASCII 可见字符直接映射，回车映射为 Return。 */
    fun sendText(text: String) {
        text.forEach { ch ->
            val keysym = when (ch) {
                '\n', '\r' -> 0xFF0D
                '\t' -> 0xFF09
                else -> if (ch.code in 0x20..0x7E) ch.code else return@forEach
            }
            key(keysym, true)
            key(keysym, false)
        }
    }

    private fun readVersion(input: DataInputStream): String {
        val bytes = ByteArray(12)
        input.readFully(bytes)
        val version = String(bytes, Charsets.US_ASCII)
        if (!version.startsWith("RFB ")) throw IllegalStateException("不是 VNC 服务：$version")
        return version
    }

    private fun writeVersion(out: DataOutputStream, serverVersion: String) {
        val major = serverVersion.substring(4, 7).trim().toIntOrNull() ?: 3
        val minor = serverVersion.substring(8, 11).trim().toIntOrNull() ?: 8
        val chosen = when {
            major > 3 || (major == 3 && minor >= 8) -> "RFB 003.008\n"
            major == 3 && minor == 7 -> "RFB 003.007\n"
            else -> "RFB 003.003\n"
        }
        out.write(chosen.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun negotiateSecurity(input: DataInputStream, out: DataOutputStream, serverVersion: String) {
        val major = serverVersion.substring(4, 7).trim().toIntOrNull() ?: 3
        val minor = serverVersion.substring(8, 11).trim().toIntOrNull() ?: 8
        if (major == 3 && minor >= 7) {
            val count = input.readUnsignedByte()
            val types = IntArray(count) { input.readUnsignedByte() }
            if (1 !in types) {
                throw IllegalStateException("服务器要求密码认证（安全类型 ${types.joinToString()}），当前仅支持无密码")
            }
            out.writeByte(1)
            out.flush()
            if (major == 3 && minor >= 8) {
                val result = input.readInt()
                if (result != 0) throw IllegalStateException("VNC 安全握手失败：$result")
            }
        } else {
            val type = input.readInt()
            if (type != 1) throw IllegalStateException("不支持的 VNC 安全类型：$type")
        }
    }

    private fun writeClientInit(out: DataOutputStream) {
        out.writeByte(1) // shared
        out.flush()
    }

    private fun readServerInit(input: DataInputStream) {
        frameWidth = input.readUnsignedShort()
        frameHeight = input.readUnsignedShort()
        input.skipFully(16) // server pixel format，稍后由客户端覆盖
        val nameLength = input.readInt()
        if (nameLength > 0) input.skipFully(nameLength)
        if (frameWidth <= 0 || frameHeight <= 0) {
            throw IllegalStateException("VNC 返回了无效分辨率：${frameWidth}x$frameHeight")
        }
        listener.onConnected(frameWidth, frameHeight)
    }

    private fun setPixelFormat(out: DataOutputStream) {
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(32) // bits-per-pixel
        out.writeByte(24) // depth
        out.writeByte(0)  // big-endian
        out.writeByte(1)  // true-colour
        out.writeShort(255) // red-max
        out.writeShort(255) // green-max
        out.writeShort(255) // blue-max
        out.writeByte(16) // red-shift
        out.writeByte(8)  // green-shift
        out.writeByte(0)  // blue-shift
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
        out.flush()
    }

    private fun setEncodings(out: DataOutputStream) {
        out.writeByte(2)
        out.writeByte(0)
        out.writeShort(1)
        out.writeInt(0) // Raw
        out.flush()
    }

    private fun requestFrame(out: DataOutputStream, incremental: Boolean) {
        out.writeByte(3)
        out.writeByte(if (incremental) 1 else 0)
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(frameWidth)
        out.writeShort(frameHeight)
        out.flush()
    }

    private fun readRawRect(
        input: DataInputStream,
        pixels: IntArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ) {
        val rowBytes = ByteArray(width * 4)
        for (row in 0 until height) {
            input.readFully(rowBytes)
            val destY = y + row
            if (destY < 0 || destY >= frameHeight) continue
            var src = 0
            for (col in 0 until width) {
                val destX = x + col
                if (destX in 0 until frameWidth) {
                    val b = rowBytes[src].toInt() and 0xFF
                    val g = rowBytes[src + 1].toInt() and 0xFF
                    val r = rowBytes[src + 2].toInt() and 0xFF
                    pixels[destY * frameWidth + destX] =
                        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                }
                src += 4
            }
        }
    }

    private fun DataInputStream.skipFully(count: Int) {
        if (count <= 0) return
        var remaining = count
        val buffer = ByteArray(4096)
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) throw java.io.EOFException("VNC 连接提前结束")
            remaining -= read
        }
    }
}

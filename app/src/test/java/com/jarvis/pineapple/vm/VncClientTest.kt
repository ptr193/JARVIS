package com.jarvis.pineapple.vm

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用本地假 VNC 服务端验证 RFB 握手、协议边界与 RAW 帧解码。
 * 不依赖 Android Framework、不连接真实 x11vnc，不代表实机 VNC 验收。
 */
class VncClientTest {

    @Test
    fun fragmentedServerMessagesAndMultipleRawRectsDecodeCorrectly() = runBlocking {
        ServerSocket(0).use { server ->
            val serverJob = launch(Dispatchers.IO) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    writeServerHandshake(input, output, width = 2, height = 1, fragmented = true)
                    readInitialClientMessages(input)

                    output.writeByte(2) // Bell
                    output.writeByte(3) // ServerCutText
                    output.writeByte(0)
                    output.writeByte(0)
                    output.writeByte(0)
                    output.writeInt(2)
                    output.write("hi".toByteArray())
                    output.writeByte(0) // FramebufferUpdate
                    output.writeByte(0)
                    output.writeShort(2)
                    writeRawRect(output, x = 0, y = 0, red = 255, green = 0)
                    writeRawRect(output, x = 1, y = 0, red = 0, green = 255)
                    output.flush()
                }
            }
            val frame = CompletableDeferred<IntArray>()
            val client = VncClient(object : VncClient.Listener {
                override fun onConnected(width: Int, height: Int) {
                    assertEquals(2, width)
                    assertEquals(1, height)
                }

                override fun onFrame(pixels: IntArray, width: Int, height: Int) {
                    if (!frame.isCompleted) frame.complete(pixels.copyOf())
                }

                override fun onDisconnected(reason: String) = Unit
            })
            val clientJob = launch(Dispatchers.IO) { client.run("127.0.0.1", server.localPort) }

            val decoded = withTimeout(5_000) { frame.await() }
            client.disconnect()
            joinWithTimeout(clientJob)
            joinWithTimeout(serverJob)

            assertArrayEquals(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), decoded)
        }
    }

    @Test
    fun rejectsInvalidAndExcessiveResolution() {
        assertDisconnectContains("无效分辨率") { input, output ->
            writeServerHandshake(input, output, width = 0, height = 1)
        }
        assertDisconnectContains("分辨率过大") { input, output ->
            writeServerHandshake(input, output, width = 65_535, height = 65_535)
        }
    }

    @Test
    fun rejectsNegativeAndOversizedDesktopName() {
        assertDisconnectContains("桌面名称长度非法") { input, output ->
            writeServerHandshake(input, output, width = 1, height = 1, nameLength = -1)
        }
        assertDisconnectContains("桌面名称长度非法") { input, output ->
            writeServerHandshake(input, output, width = 1, height = 1, nameLength = 65_537)
        }
    }

    @Test
    fun rejectsNegativeAndOversizedCutTextWithoutAllocating() {
        listOf(-1, 1_048_577).forEach { length ->
            assertDisconnectContains("CutText长度非法") { input, output ->
                writeServerHandshake(input, output, width = 1, height = 1)
                readInitialClientMessages(input)
                output.writeByte(3)
                output.writeByte(0)
                output.writeByte(0)
                output.writeByte(0)
                output.writeInt(length)
                output.flush()
            }
        }
    }

    @Test
    fun rejectsOutOfBoundsRectAndUnsupportedEncoding() {
        assertDisconnectContains("矩形越界") { input, output ->
            writeServerHandshake(input, output, width = 1, height = 1)
            readInitialClientMessages(input)
            writeRectHeader(output, x = 1, y = 0, width = 1, height = 1, encoding = 0)
            output.flush()
        }
        assertDisconnectContains("不支持的编码") { input, output ->
            writeServerHandshake(input, output, width = 1, height = 1)
            readInitialClientMessages(input)
            writeRectHeader(output, x = 0, y = 0, width = 1, height = 1, encoding = 1)
            output.flush()
        }
    }

    @Test
    fun truncatedRawFrameClosesWithVisibleError() {
        assertDisconnectContains("VNC 连接中断") { input, output ->
            writeServerHandshake(input, output, width = 1, height = 1)
            readInitialClientMessages(input)
            writeRectHeader(output, x = 0, y = 0, width = 1, height = 1, encoding = 0)
            output.writeByte(0)
            output.writeByte(0)
            output.flush()
        }
    }

    @Test
    fun activeDisconnectIsIdempotentAndReleasesConnection() = runBlocking {
        ServerSocket(0).use { server ->
            val serverReady = CompletableDeferred<Unit>()
            val serverJob = launch(Dispatchers.IO) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    writeServerHandshake(input, output, width = 1, height = 1)
                    readInitialClientMessages(input)
                    serverReady.complete(Unit)
                    assertEquals(-1, input.read())
                }
            }
            val reasons = mutableListOf<String>()
            val disconnected = CompletableDeferred<String>()
            val client = VncClient(object : VncClient.Listener {
                override fun onConnected(width: Int, height: Int) = Unit
                override fun onFrame(pixels: IntArray, width: Int, height: Int) = Unit
                override fun onDisconnected(reason: String) {
                    reasons += reason
                    disconnected.complete(reason)
                }
            })
            val clientJob = launch(Dispatchers.IO) { client.run("127.0.0.1", server.localPort) }
            withTimeout(5_000) { serverReady.await() }

            client.disconnect()
            client.disconnect()
            assertEquals("连接已关闭", withTimeout(5_000) { disconnected.await() })
            joinWithTimeout(clientJob)
            joinWithTimeout(serverJob)
            assertEquals(listOf("连接已关闭"), reasons)
        }
    }

    @Test
    fun writeFailureReturnsFalseDisconnectsAndNotifiesOnlyOnce() = runBlocking {
        val socket = FakeSocket(serverHandshakeScript())
        val callbackCount = AtomicInteger()
        val disconnected = CompletableDeferred<String>()
        val client = VncClient(object : VncClient.Listener {
            override fun onConnected(width: Int, height: Int) = Unit
            override fun onFrame(pixels: IntArray, width: Int, height: Int) = Unit
            override fun onDisconnected(reason: String) {
                callbackCount.incrementAndGet()
                disconnected.complete(reason)
            }
        }, { socket })
        val clientJob = launch(Dispatchers.IO) { client.run("fake", 5900) }
        withTimeout(5_000) { socket.initialWrites.await() }

        socket.failWrites()
        assertFalse(client.pointer(0, 0, 1))
        val reason = withTimeout(5_000) { disconnected.await() }
        assertTrue(reason, reason.contains("VNC 发送失败"))
        assertTrue(reason, reason.contains("injected write failure"))
        assertFalse(client.key(0xFF0D, true))
        assertFalse(client.sendText("a"))
        client.disconnect()
        joinWithTimeout(clientJob)
        assertEquals(1, callbackCount.get())
    }

    private fun assertDisconnectContains(
        expected: String,
        serverAction: (DataInputStream, DataOutputStream) -> Unit
    ) = runBlocking {
        ServerSocket(0).use { server ->
            val serverJob = launch(Dispatchers.IO) {
                server.accept().use { socket ->
                    serverAction(
                        DataInputStream(socket.getInputStream()),
                        DataOutputStream(socket.getOutputStream())
                    )
                }
            }
            val disconnected = CompletableDeferred<String>()
            val client = VncClient(object : VncClient.Listener {
                override fun onConnected(width: Int, height: Int) = Unit
                override fun onFrame(pixels: IntArray, width: Int, height: Int) = Unit
                override fun onDisconnected(reason: String) {
                    disconnected.complete(reason)
                }
            })
            val clientJob = launch(Dispatchers.IO) { client.run("127.0.0.1", server.localPort) }

            val reason = withTimeout(5_000) { disconnected.await() }
            client.disconnect()
            joinWithTimeout(clientJob)
            joinWithTimeout(serverJob)
            assertTrue(reason, reason.contains(expected))
        }
    }

    private suspend fun joinWithTimeout(job: Job) {
        withTimeout(5_000) { job.join() }
    }

    private fun writeServerHandshake(
        input: DataInputStream,
        output: DataOutputStream,
        width: Int,
        height: Int,
        nameLength: Int = 0,
        fragmented: Boolean = false
    ) {
        writeBytes(output, "RFB 003.008\n".toByteArray(Charsets.US_ASCII), fragmented)
        readExactly(input, 12)
        writeBytes(output, byteArrayOf(1, 1), fragmented) // None security
        readExactly(input, 1)
        writeBytes(output, byteArrayOf(0, 0, 0, 0), fragmented) // SecurityResult OK
        readExactly(input, 1) // ClientInit
        writeBytes(output, serverInit(width, height, nameLength), fragmented)
    }

    private fun serverHandshakeScript(): ByteArray {
        val bytes = ByteArrayOutputStream()
        bytes.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        bytes.write(1)
        bytes.write(1)
        bytes.write(byteArrayOf(0, 0, 0, 0))
        bytes.write(serverInit(width = 1, height = 1, nameLength = 0))
        return bytes.toByteArray()
    }

    private fun serverInit(width: Int, height: Int, nameLength: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        output.writeShort(width)
        output.writeShort(height)
        output.write(serverPixelFormat())
        output.writeInt(nameLength)
        output.flush()
        return bytes.toByteArray()
    }

    private fun readInitialClientMessages(input: DataInputStream) {
        readExactly(input, 20) // SetPixelFormat
        readExactly(input, 8) // SetEncodings
        readExactly(input, 10) // FramebufferUpdateRequest
    }

    private fun writeRawRect(output: DataOutputStream, x: Int, y: Int, red: Int, green: Int) {
        writeRect(output, x, y, width = 1, height = 1, encoding = 0)
        output.writeByte(0) // blue
        output.writeByte(green)
        output.writeByte(red)
        output.writeByte(0)
    }

    private fun writeRectHeader(
        output: DataOutputStream,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        encoding: Int
    ) {
        output.writeByte(0)
        output.writeByte(0)
        output.writeShort(1)
        writeRect(output, x, y, width, height, encoding)
    }

    private fun writeRect(
        output: DataOutputStream,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        encoding: Int
    ) {
        output.writeShort(x)
        output.writeShort(y)
        output.writeShort(width)
        output.writeShort(height)
        output.writeInt(encoding)
    }

    private fun writeBytes(output: DataOutputStream, bytes: ByteArray, fragmented: Boolean) {
        if (fragmented) {
            bytes.forEach { byte ->
                output.writeByte(byte.toInt())
                output.flush()
            }
        } else {
            output.write(bytes)
            output.flush()
        }
    }

    private fun serverPixelFormat(): ByteArray = byteArrayOf(
        32, 24, 0, 1,
        0, -1, 0, -1, 0, -1,
        16, 8, 0,
        0, 0, 0
    )

    private fun readExactly(input: InputStream, count: Int) {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) throw IllegalStateException("连接提前关闭")
            read += n
        }
    }

    private class FakeSocket(script: ByteArray) : Socket() {
        private val input = BlockingInputStream(script)
        private val output = FailingOutputStream()
        val initialWrites: CompletableDeferred<Unit> = output.initialWrites

        override fun setTcpNoDelay(on: Boolean) = Unit
        override fun connect(endpoint: SocketAddress, timeout: Int) = Unit
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun close() = input.close()
        fun failWrites() {
            output.fail = true
        }
    }

    private class BlockingInputStream(private val script: ByteArray) : InputStream() {
        private var offset = 0
        private val closed = CountDownLatch(1)

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (this.offset < script.size) {
                val count = minOf(length, script.size - this.offset)
                script.copyInto(buffer, offset, this.offset, this.offset + count)
                this.offset += count
                return count
            }
            closed.await()
            return -1
        }

        override fun close() {
            closed.countDown()
        }
    }

    private class FailingOutputStream : OutputStream() {
        @Volatile
        var fail = false
        private var byteCount = 0
        val initialWrites = CompletableDeferred<Unit>()

        override fun write(value: Int) = record(1)

        override fun write(buffer: ByteArray, offset: Int, length: Int) = record(length)

        private fun record(count: Int) {
            if (fail) throw IOException("injected write failure")
            byteCount += count
            if (byteCount >= INITIAL_CLIENT_BYTES) initialWrites.complete(Unit)
        }
    }

    private companion object {
        const val INITIAL_CLIENT_BYTES = 52
    }
}

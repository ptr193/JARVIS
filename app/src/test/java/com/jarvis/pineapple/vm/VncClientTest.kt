package com.jarvis.pineapple.vm

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用本地假 VNC 服务端验证 RFB 握手与 RAW 帧解码。
 * 不依赖 Android Framework、不连接真实 x11vnc。
 */
class VncClientTest {

    @Test
    fun connectsAndDecodesRawFrameAsArgb() = runBlocking {
        ServerSocket(0).use { server ->
            val serverJob = launch(Dispatchers.IO) { serveSingleRedPixel(server) }
            val pixel = CompletableDeferred<Int>()
            val client = VncClient(object : VncClient.Listener {
                override fun onConnected(width: Int, height: Int) = Unit

                override fun onFrame(pixels: IntArray, width: Int, height: Int) {
                    if (!pixel.isCompleted && pixels.isNotEmpty()) pixel.complete(pixels[0])
                }

                override fun onDisconnected(reason: String) = Unit
            })

            val clientJob = launch(Dispatchers.IO) {
                client.run("127.0.0.1", server.localPort)
            }

            val decoded = withTimeout(5_000) { pixel.await() }
            client.disconnect()
            clientJob.join()
            serverJob.join()

            assertEquals(0xFFFF0000.toInt(), decoded)
        }
    }

    private fun serveSingleRedPixel(server: ServerSocket) {
        server.accept().use { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            readExactly(input, 12) // Client version

            output.writeByte(1) // number of security types
            output.writeByte(1) // None
            output.flush()
            readExactly(input, 1) // chosen security type

            output.writeInt(0) // SecurityResult OK
            output.flush()
            readExactly(input, 1) // ClientInit

            output.writeShort(1) // width
            output.writeShort(1) // height
            output.write(serverPixelFormat())
            output.writeInt(0) // name length
            output.flush()

            readExactly(input, 20) // SetPixelFormat
            readExactly(input, 8)  // SetEncodings
            readExactly(input, 10) // FramebufferUpdateRequest

            output.writeByte(0) // FramebufferUpdate
            output.writeByte(0) // padding
            output.writeShort(1) // one rect
            output.writeShort(0) // x
            output.writeShort(0) // y
            output.writeShort(1) // w
            output.writeShort(1) // h
            output.writeInt(0)  // Raw encoding
            output.writeByte(0) // blue
            output.writeByte(0) // green
            output.writeByte(255) // red
            output.writeByte(0) // padding
            output.flush()
        }
    }

    private fun serverPixelFormat(): ByteArray = byteArrayOf(
        32, 24, 0, 1,               // bpp, depth, big-endian, true-colour
        0, -1, 0, -1, 0, -1,        // red/green/blue max (255)
        16, 8, 0,                   // red/green/blue shift
        0, 0, 0                     // padding
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
}

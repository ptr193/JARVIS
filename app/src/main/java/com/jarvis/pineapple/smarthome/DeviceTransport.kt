package com.jarvis.pineapple.smarthome

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL

/**
 * 智能家居传输层。
 *
 * 提供两类真实协议通道：
 * 1. SSDP（UDP 组播 M-SEARCH）：发现局域网内的 Matter/Zigbee 网关、UPnP 设备。
 * 2. HTTP 网关：向已配置的网关下发设备控制指令、拉取设备清单。
 *
 * 网关地址与令牌保存在 `jarvis_smarthome` SharedPreferences 中：
 * - `gateway_url`：网关 REST 根地址，例如 `http://192.168.1.10:8080`
 * - `gateway_token`：可选 Bearer 令牌
 *
 * 未配置网关时所有方法安全降级（返回空/失败），不会伪造成功。
 */
class DeviceTransport(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("jarvis_smarthome", Context.MODE_PRIVATE)

    data class DiscoveredDevice(
        val id: String,
        val name: String,
        val protocol: String,
        val location: String = "",
        val server: String = "",
        val rawType: String = ""
    )

    var gatewayUrl: String
        get() = prefs.getString(KEY_GATEWAY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GATEWAY_URL, value.trim().trimEnd('/')).apply()

    var gatewayToken: String
        get() = prefs.getString(KEY_GATEWAY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GATEWAY_TOKEN, value.trim()).apply()

    val isConfigured: Boolean get() = gatewayUrl.isNotEmpty()

    // ==================== SSDP 发现 ====================

    /**
     * 发送 SSDP M-SEARCH 并收集应答。
     *
     * @param timeoutMs 等待应答的总时长
     */
    suspend fun ssdpDiscover(timeoutMs: Long = 3000L): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val results = linkedMapOf<String, DiscoveredDevice>()
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("jarvis-ssdp")?.apply { setReferenceCounted(false) }
        runCatching { lock?.acquire() }
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply { soTimeout = 800; reuseAddress = true }
            val group = InetAddress.getByName(SSDP_GROUP)
            val message = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: $SSDP_GROUP:$SSDP_PORT\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 2\r\n")
                append("ST: ssdp:all\r\n")
                append("\r\n")
            }
            val payload = message.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(group, SSDP_PORT)))

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
                if (!received) continue
                val headers = parseHeaders(String(packet.data, 0, packet.length, Charsets.UTF_8))
                val usn = headers["usn"] ?: packet.address.hostAddress
                val st = headers["st"] ?: headers["nt"] ?: ""
                results[usn] = DiscoveredDevice(
                    id = usn,
                    name = headers["server"] ?: headers["location"] ?: packet.address.hostAddress,
                    protocol = inferProtocol(headers["server"] ?: "", st),
                    location = headers["location"] ?: "",
                    server = headers["server"] ?: "",
                    rawType = st
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP 发现失败: ${e.message}")
        } finally {
            runCatching { socket?.close() }
            runCatching { lock?.release() }
        }
        results.values.toList()
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        raw.split("\r\n", "\n").drop(1).forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                map[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        return map
    }

    private fun inferProtocol(server: String, st: String): String {
        val text = "$server $st".lowercase()
        return when {
            text.contains("matter") -> "MATTER"
            text.contains("homekit") || text.contains("hap") -> "HOMEKIT"
            text.contains("tuya") -> "TUYA"
            text.contains("mijia") || text.contains("miio") -> "MIJIA"
            text.contains("zigbee") -> "ZIGBEE"
            text.contains("mediarenderer") || text.contains("upnp") -> "WIFI"
            else -> "OTHER"
        }
    }

    // ==================== HTTP 网关 ====================

    /**
     * 从网关拉取设备清单。未配置网关时返回空列表。
     */
    suspend fun fetchDevices(): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()
        val response = request("GET", "/devices", null) ?: return@withContext emptyList()
        runCatching {
            val array = JSONArray(response)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                DiscoveredDevice(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    protocol = obj.optString("protocol", "OTHER"),
                    location = obj.optString("location", ""),
                    server = obj.optString("model", ""),
                    rawType = obj.optString("type", "")
                )
            }
        }.getOrElse {
            Log.w(TAG, "解析设备清单失败: ${it.message}")
            emptyList()
        }
    }

    /**
     * 向网关设备下发指令。
     *
     * @return 网关返回 2xx 时返回 true
     */
    suspend fun sendCommand(
        deviceId: String,
        command: String,
        params: Map<String, Any?> = emptyMap()
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext false
        val body = JSONObject().apply {
            put("command", command)
            put("params", JSONObject(params as Map<*, *>))
            put("ts", System.currentTimeMillis())
        }.toString()
        request("POST", "/devices/$deviceId/command", body) != null
    }

    /**
     * 查询设备当前状态。
     */
    suspend fun fetchState(deviceId: String): JSONObject? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        val response = request("GET", "/devices/$deviceId/state", null) ?: return@withContext null
        runCatching { JSONObject(response) }.getOrNull()
    }

    private fun request(method: String, path: String, body: String?): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("$gatewayUrl$path")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 4000
                readTimeout = 6000
                setRequestProperty("Accept", "application/json")
                if (gatewayToken.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $gatewayToken")
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
            } ?: ""
            if (connection.responseCode in 200..299) {
                text
            } else {
                Log.w(TAG, "$method $path -> HTTP ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP 请求失败 $method $path: ${e.message}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    companion object {
        private const val TAG = "DeviceTransport"
        private const val KEY_GATEWAY_URL = "gateway_url"
        private const val KEY_GATEWAY_TOKEN = "gateway_token"
        private const val SSDP_GROUP = "239.255.255.250"
        private const val SSDP_PORT = 1900
    }
}

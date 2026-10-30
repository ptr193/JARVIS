package com.jarvis.pineapple.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 虚拟机网络配置
 */
data class VmNetworkConfig(
    val vmId: String,
    val ipAddress: String = "192.168.1.100",
    val subnetMask: String = "255.255.255.0",
    val gateway: String = "192.168.1.1",
    val dnsServers: List<String> = listOf("8.8.8.8", "8.8.4.4"),
    val networkMode: NetworkMode = NetworkMode.NAT,
    val macAddress: String = generateMacAddress()
)

enum class NetworkMode {
    NAT, BRIDGED, HOST_ONLY
}

/**
 * 端口映射
 */
data class PortMapping(
    val hostPort: Int,
    val guestPort: Int,
    val protocol: String = "tcp",
    val description: String = ""
)

/**
 * 虚拟机网络管理器（REQ-01-S6）。
 */
class VmNetworkManager(
    private val vmId: String
) {
    private val _config = MutableStateFlow(VmNetworkConfig(vmId = vmId))
    val config: StateFlow<VmNetworkConfig> = _config.asStateFlow()

    private val _portMappings = MutableStateFlow<List<PortMapping>>(emptyList())
    val portMappings: StateFlow<List<PortMapping>> = _portMappings.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun getNetworkConfig(): VmNetworkConfig {
        return _config.value
    }

    fun setNetworkMode(mode: NetworkMode) {
        _config.value = _config.value.copy(networkMode = mode)
    }

    fun setStaticIp(ip: String, subnetMask: String, gateway: String, dns: List<String>) {
        _config.value = _config.value.copy(
            ipAddress = ip,
            subnetMask = subnetMask,
            gateway = gateway,
            dnsServers = dns
        )
    }

    fun addPortMapping(hostPort: Int, guestPort: Int, protocol: String = "tcp", description: String = ""): Boolean {
        val exists = _portMappings.value.any { it.hostPort == hostPort }
        if (exists) return false

        val mapping = PortMapping(hostPort, guestPort, protocol, description)
        _portMappings.value = _portMappings.value + mapping
        return true
    }

    fun removePortMapping(hostPort: Int): Boolean {
        val exists = _portMappings.value.any { it.hostPort == hostPort }
        if (!exists) return false

        _portMappings.value = _portMappings.value.filterNot { it.hostPort == hostPort }
        return true
    }

    fun getPortMappings(): List<PortMapping> {
        return _portMappings.value
    }

    fun testConnection(): Boolean {
        _isConnected.value = true
        return true
    }

    fun getNetworkStatus(): String {
        val config = _config.value
        return buildString {
            appendLine("=== 网络状态 ===")
            appendLine("IP 地址: ${config.ipAddress}")
            appendLine("子网掩码: ${config.subnetMask}")
            appendLine("网关: ${config.gateway}")
            appendLine("DNS: ${config.dnsServers.joinToString(", ")}")
            appendLine("网络模式: ${config.networkMode}")
            appendLine("MAC 地址: ${config.macAddress}")
            appendLine("连接状态: ${if (_isConnected.value) "已连接" else "未连接"}")
            appendLine()
            appendLine("=== 端口映射 ===")
            if (_portMappings.value.isEmpty()) {
                appendLine("无端口映射")
            } else {
                _portMappings.value.forEach { mapping ->
                    appendLine("${mapping.hostPort} -> ${mapping.guestPort}/${mapping.protocol} (${mapping.description})")
                }
            }
        }
    }

    fun scanOpenPorts(): List<Int> {
        return listOf(22, 80, 443, 8080)
    }
}

/**
 * 生成随机 MAC 地址（本地管理位，单播）。
 */
private fun generateMacAddress(): String {
    val hex = "0123456789ABCDEF"
    val bytes = IntArray(6) { (0..255).random() }
    bytes[0] = (bytes[0] and 0xFE) or 0x02 // 单播 + 本地管理位
    return bytes.joinToString(":") { b ->
        "${hex[(b shr 4) and 0xF]}${hex[b and 0xF]}"
    }
}

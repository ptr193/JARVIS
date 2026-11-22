package com.jarvis.pineapple.system

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager

/**
 * USB 设备探测（#73）。
 *
 * 基于真实 UsbManager.deviceList 列出当前连接的 USB 设备（主机模式）。
 * 列表读取不需要通信权限；hasPermission 仅表示是否可与设备通信。
 * 探测失败/无服务返回空列表，不伪装成功。
 */
class UsbDeviceManager(private val context: Context) {

    data class UsbDeviceInfo(
        val name: String,
        val vendorId: Int,
        val productId: Int,
        val deviceClass: Int,
        val hasPermission: Boolean
    )

    fun hasUsbHostFeature(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    /** 列出当前连接的 USB 设备；无 USB 服务或异常时返回空列表。 */
    fun probeUsbDevices(): List<UsbDeviceInfo> = try {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return emptyList()
        usb.deviceList.values.map { device ->
            UsbDeviceInfo(
                name = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                deviceClass = device.deviceClass,
                hasPermission = usb.hasPermission(device)
            )
        }.sortedBy { it.name }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        /** 纯逻辑：把探测结果格式化为可读摘要，便于单测。 */
        fun summarize(devices: List<UsbDeviceInfo>): String =
            if (devices.isEmpty()) {
                "未检测到 USB 设备"
            } else {
                "检测到 ${devices.size} 个 USB 设备：\n" +
                    devices.joinToString("\n") {
                        "· ${it.name} (VID 0x${it.vendorId.toString(16)}, " +
                            "PID 0x${it.productId.toString(16)}, class ${it.deviceClass})" +
                            if (it.hasPermission) "" else " [无通信权限]"
                    }
            }
    }
}

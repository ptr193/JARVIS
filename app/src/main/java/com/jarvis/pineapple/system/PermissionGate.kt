package com.jarvis.pineapple.system

/**
 * 系统权限失败关闭：缺少权限时返回中文原因，不伪造成功。
 */
object PermissionGate {

    fun deniedMessage(permission: String): String = "缺少 $permission 权限"

    fun allowOrDenied(granted: Boolean, permission: String): String? =
        if (granted) null else deniedMessage(permission)

    fun phoneCallAllowed(hasCallPhone: Boolean): Boolean = hasCallPhone

    fun smsSendAllowed(hasSendSms: Boolean): Boolean = hasSendSms

    fun calendarWriteAllowed(hasWriteCalendar: Boolean): Boolean = hasWriteCalendar

    fun wifiChangeAllowed(hasChangeWifiState: Boolean): Boolean = hasChangeWifiState

    fun bluetoothChangeAllowed(hasBluetoothPermission: Boolean): Boolean = hasBluetoothPermission

    fun brightnessWriteAllowed(canWriteSettings: Boolean): Boolean = canWriteSettings
}

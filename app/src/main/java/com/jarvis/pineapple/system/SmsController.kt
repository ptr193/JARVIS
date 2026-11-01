package com.jarvis.pineapple.system

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/**
 * 短信控制：读取 / 发送 / 转发
 * 所有敏感操作前检查运行时权限
 */
class SmsController(private val context: Context) {

    fun send(phoneNumber: String, message: String): Boolean {
        if (!PermissionGate.smsSendAllowed(hasSendSmsPermission())) return false
        val smsManager = context.getSystemService(SmsManager::class.java)
        smsManager.sendTextMessage(phoneNumber, null, message, null, null)
        return true
    }

    fun forward(phoneNumber: String, message: String): Boolean = send(phoneNumber, message)

    fun readInbox(): List<String> {
        if (!hasReadSmsPermission()) return emptyList()
        val msgs = mutableListOf<String>()
        val cursor = context.contentResolver.query(
            android.net.Uri.parse("content://sms/inbox"), null, null, null, null
        )
        cursor?.use {
            val bodyIdx = it.getColumnIndex("body")
            val addrIdx = it.getColumnIndex("address")
            while (it.moveToNext()) {
                msgs.add("${it.getString(addrIdx)}: ${it.getString(bodyIdx)}")
            }
        }
        return msgs
    }

    // ==================== 权限检查 ====================

    fun hasSendSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasReadSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }
}

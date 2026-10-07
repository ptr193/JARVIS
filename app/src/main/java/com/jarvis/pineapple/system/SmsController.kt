package com.jarvis.pineapple.system

import android.content.Context
import android.content.Intent
import android.telephony.SmsManager

/**
 * 短信控制：读取 / 发送 / 转发
 */
class SmsController(private val context: Context) {

    fun send(phoneNumber: String, message: String) {
        val smsManager = context.getSystemService(SmsManager::class.java)
        smsManager.sendTextMessage(phoneNumber, null, message, null, null)
    }

    fun forward(phoneNumber: String, message: String) = send(phoneNumber, message)

    fun readInbox(): List<String> {
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
}

package com.jarvis.pineapple.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

/**
 * 电话控制：拨打 / 接听 / 通话记录
 */
class PhoneController(private val context: Context) {

    fun call(phoneNumber: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun answerCall() {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            telecom.acceptRingingCall()
        }
    }

    fun readCallLogs(): List<String> {
        val logs = mutableListOf<String>()
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, null, null, null, "${CallLog.Calls.DATE} DESC"
        )
        cursor?.use {
            val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
            val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
            while (it.moveToNext()) {
                logs.add("${it.getString(numberIdx)} - ${it.getLong(dateIdx)}")
            }
        }
        return logs
    }
}

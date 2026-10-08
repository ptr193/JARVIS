package com.jarvis.pineapple.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi

/**
 * 电话控制：拨打 / 接听 / 通话记录
 * 调用方需确保已获得相应运行时权限。
 */
class PhoneController(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun call(phoneNumber: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    fun answerCall() {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        telecom.acceptRingingCall()
    }

    @SuppressLint("MissingPermission")
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

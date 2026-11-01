package com.jarvis.pineapple.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * 电话控制：拨打 / 接听 / 通话记录
 * 所有敏感操作前检查运行时权限
 */
class PhoneController(private val context: Context) {

    fun call(phoneNumber: String): Boolean {
        if (!PermissionGate.phoneCallAllowed(hasCallPermission())) return false
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun answerCall(): Boolean {
        if (!hasAnswerPermission()) return false
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        telecom.acceptRingingCall()
        return true
    }

    fun readCallLogs(): List<String> {
        if (!hasCallLogPermission()) return emptyList()
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

    // ==================== 权限检查 ====================

    fun hasCallPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAnswerPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCallLogPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasPhoneStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

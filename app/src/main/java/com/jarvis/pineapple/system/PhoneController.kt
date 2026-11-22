package com.jarvis.pineapple.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/** 接听来电的前置条件状态（纯逻辑，便于单测）。 */
enum class AnswerReadiness { READY, UNSUPPORTED_SDK, PERMISSION_REQUIRED, NOT_DEFAULT_DIALER }

/**
 * 电话控制：拨打 / 接听 / 通话记录
 * 所有敏感操作前检查运行时权限
 */
class PhoneController(private val context: Context) {

    fun call(phoneNumber: String): Boolean {
        if (!PermissionGate.phoneCallAllowed(hasCallPermission()) || phoneNumber.isBlank()) return false
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    fun answerCall(): Boolean {
        if (!hasAnswerPermission()) return false
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.acceptRingingCall()
            true
        } catch (e: SecurityException) {
            // 未持有默认拨号角色等情况下系统会拒绝，不得伪装成功
            false
        }
    }

    /** 是否已被系统设为默认电话应用（接听来电的必要角色）。 */
    fun isDefaultDialer(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
            roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) == true
        } else {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("DEPRECATION")
            telecom?.defaultDialerPackage == context.packageName
        }
    } catch (_: Exception) {
        false
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

    companion object {
        /** 纯逻辑：根据 SDK / 权限 / 默认拨号角色判断是否可接听来电，便于脱离 Context 单测。 */
        fun answerReadiness(
            sdkInt: Int,
            hasAnswerPermission: Boolean,
            isDefaultDialer: Boolean
        ): AnswerReadiness = when {
            sdkInt < Build.VERSION_CODES.O -> AnswerReadiness.UNSUPPORTED_SDK
            !hasAnswerPermission -> AnswerReadiness.PERMISSION_REQUIRED
            !isDefaultDialer -> AnswerReadiness.NOT_DEFAULT_DIALER
            else -> AnswerReadiness.READY
        }
    }
}

package com.jarvis.pineapple.system

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 运行时权限管理器（#42 权限管控与安全响应）。
 *
 * 统一管理敏感权限的申请与检查：
 * - 电话：CALL_PHONE, READ_PHONE_STATE, ANSWER_PHONE_CALLS
 * - 短信：SEND_SMS, READ_SMS
 * - 日历：READ_CALENDAR, WRITE_CALENDAR
 * - 通知：POST_NOTIFICATIONS (API 33+)
 * - 位置：ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION
 */
object PermissionManager {

    /** 电话相关权限 */
    val PHONE_PERMISSIONS = arrayOf(
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.ANSWER_PHONE_CALLS
    )

    /** 短信相关权限 */
    val SMS_PERMISSIONS = arrayOf(
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.READ_SMS
    )

    /** 日历相关权限 */
    val CALENDAR_PERMISSIONS = arrayOf(
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR
    )

    /** 通知权限 (Android 13+) */
    val NOTIFICATION_PERMISSIONS = arrayOf(
        android.Manifest.permission.POST_NOTIFICATIONS
    )

    /** 位置相关权限 */
    val LOCATION_PERMISSIONS = arrayOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION
    )

    /** 麦克风相关权限 */
    val MICROPHONE_PERMISSIONS = arrayOf(
        android.Manifest.permission.RECORD_AUDIO
    )

    /** 相机相关权限 */
    val CAMERA_PERMISSIONS = arrayOf(
        android.Manifest.permission.CAMERA
    )

    /**
     * 检查权限是否已授予
     */
    fun hasPermissions(context: Context, permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 请求权限（需在 Activity 中调用）
     */
    fun requestPermissions(activity: Activity, permissions: Array<String>, requestCode: Int) {
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, notGranted, requestCode)
        }
    }

    /**
     * 请求电话权限
     */
    fun requestPhonePermissions(activity: Activity) {
        requestPermissions(activity, PHONE_PERMISSIONS, 1001)
    }

    /**
     * 请求短信权限
     */
    fun requestSmsPermissions(activity: Activity) {
        requestPermissions(activity, SMS_PERMISSIONS, 1002)
    }

    /**
     * 请求日历权限
     */
    fun requestCalendarPermissions(activity: Activity) {
        requestPermissions(activity, CALENDAR_PERMISSIONS, 1003)
    }

    /**
     * 请求通知权限
     */
    fun requestNotificationPermissions(activity: Activity) {
        requestPermissions(activity, NOTIFICATION_PERMISSIONS, 1004)
    }

    /**
     * 请求位置权限
     */
    fun requestLocationPermissions(activity: Activity) {
        requestPermissions(activity, LOCATION_PERMISSIONS, 1005)
    }

    /**
     * 请求麦克风权限
     */
    fun requestMicrophonePermissions(activity: Activity) {
        requestPermissions(activity, MICROPHONE_PERMISSIONS, 1006)
    }

    /**
     * 请求相机权限
     */
    fun requestCameraPermissions(activity: Activity) {
        requestPermissions(activity, CAMERA_PERMISSIONS, 1007)
    }

    /**
     * 检查是否需要显示权限说明
     */
    fun shouldShowRequestPermissionRationale(
        activity: Activity,
        permissions: Array<String>
    ): Boolean {
        return permissions.any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
    }
}

package com.jarvis.pineapple.system

/**
 * 按需权限说明。危险权限仅在用户点击对应功能后弹出用途说明，再请求系统对话框。
 */
object PermissionExplainer {

    const val MIC = "麦克风用于语音唤醒与语音对话"
    const val PHONE = "电话权限用于拨打、接听与读取通话记录"
    const val SMS = "短信权限用于发送与读取短信"
    const val CALENDAR = "日历权限用于创建与查看日程"
    const val CAMERA = "相机权限用于拍照、人脸识别与物体检测"
    const val LOCATION = "定位权限用于导航与位置相关功能"

    fun rationaleFor(permissions: Array<String>): String {
        val texts = linkedSetOf<String>()
        permissions.forEach { permission ->
            when (permission) {
                android.Manifest.permission.RECORD_AUDIO -> texts += MIC
                android.Manifest.permission.CALL_PHONE,
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.ANSWER_PHONE_CALLS,
                android.Manifest.permission.READ_CALL_LOG -> texts += PHONE
                android.Manifest.permission.SEND_SMS,
                android.Manifest.permission.READ_SMS -> texts += SMS
                android.Manifest.permission.READ_CALENDAR,
                android.Manifest.permission.WRITE_CALENDAR -> texts += CALENDAR
                android.Manifest.permission.CAMERA -> texts += CAMERA
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION -> texts += LOCATION
            }
        }
        return texts.joinToString("；").ifBlank { "该功能需要授予对应权限后才能使用" }
    }
}

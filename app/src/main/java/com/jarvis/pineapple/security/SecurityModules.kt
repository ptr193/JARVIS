package com.jarvis.pineapple.security

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity

/**
 * 生物识别：人脸 / 指纹 / 虹膜
 */
class BiometricAuth(private val context: Context) {

    fun canAuthenticate(): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun authenticate(activity: FragmentActivity, callback: BiometricPrompt.AuthenticationCallback) {
        val executor = context.mainExecutor
        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("JARVIS 身份验证")
            .setSubtitle("请验证您的身份")
            .setNegativeButtonText("取消")
            .build()
        prompt.authenticate(info)
    }
}

/**
 * 权限分级：主人 / 访客 / 临时
 */
enum class PermissionLevel { OWNER, GUEST, TEMPORARY }

class PermissionController {
    private var currentLevel = PermissionLevel.OWNER

    fun setLevel(level: PermissionLevel) { currentLevel = level }
    fun getLevel(): PermissionLevel = currentLevel

    fun canPerform(action: String): Boolean = when (currentLevel) {
        PermissionLevel.OWNER -> true
        PermissionLevel.GUEST -> action !in RESTRICTED_ACTIONS
        PermissionLevel.TEMPORARY -> action in TEMPORARY_ALLOWED
    }

    companion object {
        private val RESTRICTED_ACTIONS = setOf("wipe_data", "change_personality", "factory_reset")
        private val TEMPORARY_ALLOWED = setOf("make_call", "send_sms")
    }
}

/**
 * 防盗与远程控制
 */
class AntiTheftManager(private val context: Context) {

    fun lockDevice() {
        // 通过 DevicePolicyManager 锁定
    }

    fun wipeData() {
        // DevicePolicyManager.wipeData()
    }

    fun getLocation(): android.location.Location? = null // 预留
}

/**
 * 安全与隐私风险评估
 */
class SecurityAuditor {

    data class RiskReport(
        val permissionRisks: List<String>,
        val privacyRisks: List<String>,
        val securityRisks: List<String>
    )

    suspend fun audit(): RiskReport {
        // 预留：扫描权限使用、数据流、异常行为
        return RiskReport(emptyList(), emptyList(), emptyList())
    }
}

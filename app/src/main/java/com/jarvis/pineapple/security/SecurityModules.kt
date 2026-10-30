package com.jarvis.pineapple.security

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
 * 防盗与远程控制（基于 DevicePolicyManager）
 */
class AntiTheftManager(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
    private val admin = JarvisDeviceAdminReceiver.componentName(context)

    /** 设备管理员是否已激活。 */
    fun isAdminActive(): Boolean = dpm.isAdminActive(admin)

    /** 引导用户激活设备管理员（需要 Activity）。 */
    fun requestAdmin(activity: android.app.Activity) {
        val intent = android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "用于远程锁定与擦除设备（防盗功能）"
            )
        }
        activity.startActivity(intent)
    }

    /** 立即锁定屏幕。 */
    fun lockDevice() {
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
        }
    }

    /** 擦除设备数据（恢复出厂）。 */
    fun wipeData() {
        if (dpm.isAdminActive(admin)) {
            dpm.wipeData(0)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun getLocation(): android.location.Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
        if (!hasLocationPermission()) return null
        return try {
            lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
    }

    /** 请求一次定位更新（远程定位用）。 */
    @android.annotation.SuppressLint("MissingPermission")
    fun requestLocationUpdate(listener: android.location.LocationListener): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return false
        if (!hasLocationPermission()) return false
        return try {
            lm.requestLocationUpdates(
                android.location.LocationManager.GPS_PROVIDER,
                10_000L,
                10f,
                listener
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

/**
 * 安全与隐私风险评估。
 *
 * 通过 PackageManager / Settings / DevicePolicyManager 等系统接口
 * 对权限授予、隐私暴露面与系统安全配置进行真实扫描。
 */
class SecurityAuditor(private val context: Context) {

    data class RiskReport(
        val permissionRisks: List<String>,
        val privacyRisks: List<String>,
        val securityRisks: List<String>
    )

    suspend fun audit(): RiskReport = withContext(Dispatchers.IO) {
        RiskReport(
            permissionRisks = auditPermissions(),
            privacyRisks = auditPrivacy(),
            securityRisks = auditSecurity()
        )
    }

    /** 统计各应用已授予的敏感权限数量。 */
    private fun auditPermissions(): List<String> {
        val risks = mutableListOf<String>()
        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            for (pkg in packages) {
                val requested = pkg.requestedPermissions ?: continue
                val flags = pkg.requestedPermissionsFlags ?: IntArray(0)
                val grantedSensitive = requested.filterIndexed { index, permission ->
                    permission in SENSITIVE_PERMISSIONS &&
                        index < flags.size &&
                        (flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                }
                if (grantedSensitive.size >= 3) {
                    risks.add("${pkg.packageName} 已获得 ${grantedSensitive.size} 项敏感权限")
                }
            }
        } catch (e: Exception) {
            // 忽略查询异常
        }
        return risks.take(20)
    }

    private fun auditPrivacy(): List<String> {
        val risks = mutableListOf<String>()
        if (isAccessibilityServiceEnabled()) {
            risks.add("已启用无障碍服务，存在读取屏幕内容的风险")
        }
        if (isNotificationListenerEnabled()) {
            risks.add("已启用通知监听，存在读取通知内容的风险")
        }
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            risks.add("应用持有录音权限，请注意麦克风隐私")
        }
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            risks.add("应用持有精确位置权限")
        }
        return risks
    }

    private fun auditSecurity(): List<String> {
        val risks = mutableListOf<String>()
        try {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (keyguard == null || !keyguard.isDeviceSecure) {
                risks.add("设备未设置锁屏密码")
            }
        } catch (e: Exception) {
            // 忽略
        }
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1) {
            risks.add("USB 调试已启用")
        }
        if (Settings.Global.getInt(context.contentResolver, "development_settings_enabled", 0) == 1) {
            risks.add("开发者选项已启用")
        }
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            if (dpm != null && !dpm.isAdminActive(JarvisDeviceAdminReceiver.componentName(context))) {
                risks.add("设备管理员未激活，防盗功能受限")
            }
        } catch (e: Exception) {
            // 忽略
        }
        if (isRooted()) {
            risks.add("检测到设备可能已 root")
        }
        return risks
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.startsWith(context.packageName) }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(":").any { it.startsWith(context.packageName) }
    }

    private fun isRooted(): Boolean {
        val paths = listOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun hasPermission(permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private val SENSITIVE_PERMISSIONS = setOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.BODY_SENSORS
        )
    }
}

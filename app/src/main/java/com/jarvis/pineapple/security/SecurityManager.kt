package com.jarvis.pineapple.security

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark as MlKitFaceLandmark
import android.provider.Settings
import java.io.File
import java.util.concurrent.TimeUnit

/** 人脸特征灰度网格尺寸。 */
private const val FACE_GRID = 16

/** 被视为敏感的运行时权限。 */
private val DANGEROUS_PERMISSIONS = setOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.READ_SMS,
    Manifest.permission.SEND_SMS,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
    Manifest.permission.BODY_SENSORS,
    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.WRITE_EXTERNAL_STORAGE
)

/**
 * 安全管理器（功能 #34-#48）。
 *
 * 功能：
 * - 人脸检测
 * - 人脸区域图像相似度比对
 * - 访问权限分级
 * - 手机锁定与防盗
 * - 本机数据擦除
 * - 敏感权限授权审计
 * - 安全策略效能评估
 * - 隐私风险评估
 * - 安全风险评估
 * - 防御部署
 * - 安全协议执行
 */
class SecurityManager(private val context: Context) {

    private val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = JarvisDeviceAdminReceiver.componentName(context)

    // ==================== 人脸检测（#34） ====================

    /**
     * 人脸检测结果
     */
    data class FaceRecognitionResult(
        val isDetected: Boolean = false,
        val faceCount: Int = 0,
        val boundingBoxes: List<FaceBoundingBox> = emptyList(),
        val landmarks: List<FaceLandmark> = emptyList()
    )

    data class FaceBoundingBox(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    data class FaceLandmark(
        val type: LandmarkType,
        val x: Float,
        val y: Float
    )

    enum class LandmarkType {
        LEFT_EYE, RIGHT_EYE, NOSE, MOUTH_LEFT, MOUTH_RIGHT
    }

    /**
     * 检测人脸
     * 使用 ML Kit Face Detection
     */
    fun detectFaces(imageData: ByteArray): FaceRecognitionResult {
        val bitmap = try {
            BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
        } catch (e: Exception) {
            null
        } ?: return FaceRecognitionResult(isDetected = false, faceCount = 0)
        return detectFaces(bitmap)
    }

    /**
     * 使用 ML Kit Face Detection 检测人脸（同步等待结果）。
     */
    fun detectFaces(bitmap: Bitmap): FaceRecognitionResult {
        return try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.1f)
                .build()
            val detector = FaceDetection.getClient(options)
            val faces = try {
                Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 5, TimeUnit.SECONDS)
            } finally {
                detector.close()
            }
            if (faces.isEmpty()) {
                FaceRecognitionResult(isDetected = false, faceCount = 0)
            } else {
                val boxes = faces.map { face ->
                    val b = face.boundingBox
                    FaceBoundingBox(b.left, b.top, b.right, b.bottom)
                }
                val landmarks = faces.flatMap { face ->
                    face.allLandmarks.mapNotNull { lm ->
                        mapLandmark(lm.landmarkType)?.let { FaceLandmark(it, lm.position.x, lm.position.y) }
                    }
                }
                FaceRecognitionResult(
                    isDetected = true,
                    faceCount = faces.size,
                    boundingBoxes = boxes,
                    landmarks = landmarks
                )
            }
        } catch (e: Exception) {
            FaceRecognitionResult(isDetected = false, faceCount = 0)
        }
    }

    private fun mapLandmark(mlKitType: Int): LandmarkType? = when (mlKitType) {
        MlKitFaceLandmark.LEFT_EYE -> LandmarkType.LEFT_EYE
        MlKitFaceLandmark.RIGHT_EYE -> LandmarkType.RIGHT_EYE
        MlKitFaceLandmark.NOSE_BASE -> LandmarkType.NOSE
        MlKitFaceLandmark.MOUTH_LEFT -> LandmarkType.MOUTH_LEFT
        MlKitFaceLandmark.MOUTH_RIGHT -> LandmarkType.MOUTH_RIGHT
        else -> null
    }

    // ==================== 人脸区域图像相似度（#35） ====================

    data class FaceComparisonResult(
        val isComparable: Boolean = false,
        val isSimilar: Boolean = false,
        val similarity: Float = 0f,
        val threshold: Float = 0.8f,
        val details: String = ""
    )

    /** 比对两张图片中唯一人脸的区域，不作为身份认证结论。 */
    fun compareFaces(face1: ByteArray, face2: ByteArray): FaceComparisonResult {
        val similarity = calculateFaceSimilarity(face1, face2)
            ?: return FaceComparisonResult(details = "两张图片均需检测到且仅检测到一张人脸")
        return FaceComparisonResult(
            isComparable = true,
            isSimilar = similarity >= 0.8f,
            similarity = similarity,
            details = "人脸区域图像相似度：${"%.2f".format(similarity * 100)}%（非身份认证）"
        )
    }

    /** 人脸区域的灰度归一化互相关与 HOG 余弦相似度。 */
    private fun calculateFaceSimilarity(face1: ByteArray, face2: ByteArray): Float? {
        val v1 = grayscaleVector(face1) ?: return null
        val v2 = grayscaleVector(face2) ?: return null
        val ncc = normalizedCrossCorrelation(v1, v2)
        val hog = hogCosineSimilarity(v1, v2)
        return (((ncc + 1f) / 2f) * 0.5f + hog * 0.5f).coerceIn(0f, 1f)
    }

    /** 解码唯一人脸区域为归一化灰度向量；无人脸或多人脸时失败关闭。 */
    private fun grayscaleVector(data: ByteArray): FloatArray? {
        if (data.isEmpty()) return null
        val bitmap = try {
            BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (e: Exception) {
            null
        } ?: return null
        val detection = detectFaces(bitmap)
        if (!detection.isDetected || detection.faceCount != 1) return null
        val box = detection.boundingBoxes.single()
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
        val face = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val size = FACE_GRID
        val scaled = Bitmap.createScaledBitmap(face, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        val gray = FloatArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        val mean = gray.average().toFloat()
        val variance = gray.sumOf { ((it - mean).toDouble() * (it - mean)) } / gray.size
        if (variance < 1e-8) return FloatArray(gray.size)
        val std = kotlin.math.sqrt(variance).toFloat()
        return FloatArray(gray.size) { ((gray[it] - mean) / std) }
    }

    private fun normalizedCrossCorrelation(v1: FloatArray, v2: FloatArray): Float {
        val n = minOf(v1.size, v2.size)
        if (n == 0) return 0f
        var dot = 0f
        for (i in 0 until n) dot += v1[i] * v2[i]
        return (dot / n).coerceIn(-1f, 1f)
    }

    private fun hogCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        val h1 = orientationHistogram(v1)
        val h2 = orientationHistogram(v2)
        val dot = h1.indices.sumOf { (h1[it] * h2[it]).toDouble() }.toFloat()
        val n1 = kotlin.math.sqrt(h1.sumOf { (it * it).toDouble() }).toFloat()
        val n2 = kotlin.math.sqrt(h2.sumOf { (it * it).toDouble() }).toFloat()
        return if (n1 > 1e-6f && n2 > 1e-6f) (dot / (n1 * n2)).coerceIn(0f, 1f) else 0f
    }

    private fun orientationHistogram(v: FloatArray): FloatArray {
        val bins = FloatArray(8)
        val n = FACE_GRID
        for (y in 1 until n - 1) {
            for (x in 1 until n - 1) {
                val gx = v[y * n + x + 1] - v[y * n + x - 1]
                val gy = v[(y + 1) * n + x] - v[(y - 1) * n + x]
                val mag = kotlin.math.hypot(gx, gy)
                if (mag < 1e-4f) continue
                var angle = Math.toDegrees(kotlin.math.atan2(gy, gx).toDouble())
                if (angle < 0) angle += 180.0
                val bin = ((angle / 180.0) * bins.size).toInt().coerceIn(0, bins.size - 1)
                bins[bin] += mag
            }
        }
        return bins
    }

    // ==================== 手机锁定与防盗（#39） ====================

    /**
     * 锁定设备（[message]/[phoneNumber] 为防盗失主信息，写入安全审计日志；
     * 注：锁屏 OwnerInfo 仅有隐藏 API，公开 SDK 不提供，故此处落审计日志供追踪）
     */
    fun lockDevice(message: String = "", phoneNumber: String = ""): Boolean {
        return try {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                if (message.isNotBlank() || phoneNumber.isNotBlank()) {
                    android.util.Log.i(
                        "JarvisSecurity",
                        "LOCK_DEVICE with ownerInfo: msg='$message' phone='$phoneNumber'"
                    )
                }
                devicePolicyManager.lockNow()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 设置设备锁定密码
     */
    fun setDevicePassword(password: String): Boolean {
        return try {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.resetPassword(password, DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    enum class RiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    // ==================== 本机数据擦除（#41） ====================

    /** 通过设备管理器擦除本机数据。 */
    fun wipeDeviceData(includeExternalStorage: Boolean = false): Boolean {
        return try {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.wipeData(
                    if (includeExternalStorage) {
                        DevicePolicyManager.WIPE_EXTERNAL_STORAGE
                    } else {
                        0
                    }
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 权限管控与安全响应（#42） ====================

    /** 已授予应用的敏感权限；不代表该权限已被实际调用。 */
    data class PermissionGrantRecord(
        val permission: String,
        val packageName: String
    )

    /** 扫描其他应用已获授予的敏感权限。Android 不提供第三方权限调用次数与时间。 */
    fun checkPermissionGrants(): List<PermissionGrantRecord> {
        val result = mutableListOf<PermissionGrantRecord>()
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            for (pkg in packages) {
                val requested = pkg.requestedPermissions ?: continue
                val flags = pkg.requestedPermissionsFlags ?: IntArray(0)
                requested.forEachIndexed { index, permission ->
                    if (permission in DANGEROUS_PERMISSIONS &&
                        index < flags.size &&
                        (flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    ) {
                        result.add(
                            PermissionGrantRecord(
                                permission = permission.substringAfterLast('.'),
                                packageName = pkg.packageName
                            )
                        )
                    }
                }
            }
            result
        } catch (e: Exception) {
            result
        }
    }

    data class SecurityEvent(
        val type: Type,
        val description: String,
        val timestamp: Long = System.currentTimeMillis(),
        val severity: Severity = Severity.MEDIUM
    ) {
        enum class Type {
            UNAUTHORIZED_ACCESS, SUSPICIOUS_ACTIVITY, MALWARE_DETECTED,
            PRIVACY_VIOLATION, DATA_BREACH, OTHER
        }

        enum class Severity {
            LOW, MEDIUM, HIGH, CRITICAL
        }
    }

    // ==================== 安全策略效能评估（#43） ====================

    /**
     * 安全策略评估结果
     */
    data class SecurityPolicyEvaluation(
        val overallScore: Float,
        val policyEffectiveness: Map<String, Float>,
        val recommendations: List<String>,
        val evaluationTime: Long = System.currentTimeMillis()
    )

    // ==================== 安全策略效能评估（#43） ====================

    /**
     * 评估安全策略效能（基于实际系统状态）
     */
    fun evaluateSecurityPolicy(): SecurityPolicyEvaluation {
        val policies = mapOf(
            "生物识别" to evaluateBiometricPolicy(),
            "密码策略" to evaluatePasswordPolicy(),
            "本机擦除" to evaluateDeviceWipePolicy()
        )

        val overallScore = policies.values.average().toFloat()

        val recommendations = mutableListOf<String>()
        if (policies["生物识别"]!! < 0.8f) {
            recommendations.add("建议启用多因素生物识别")
        }
        if (policies["密码策略"]!! < 0.8f) {
            recommendations.add("建议加强密码复杂度要求")
        }

        return SecurityPolicyEvaluation(
            overallScore = overallScore,
            policyEffectiveness = policies,
            recommendations = recommendations
        )
    }

    private fun evaluateBiometricPolicy(): Float {
        // 基于生物识别硬件和配置的真实评估
        val biometricManager = androidx.biometric.BiometricManager.from(context)
        val canAuth = biometricManager.canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
        )
        return when (canAuth) {
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> 0.9f
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> 0.6f
            else -> 0.3f
        }
    }

    private fun evaluatePasswordPolicy(): Float {
        // 基于设备密码配置的真实评估
        return try {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (keyguardManager.isDeviceSecure) 0.85f else 0.4f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun evaluateDeviceWipePolicy(): Float {
        // 基于设备管理器的真实评估
        return try {
            if (devicePolicyManager.isAdminActive(adminComponent)) 0.95f else 0.3f
        } catch (e: Exception) {
            0.5f
        }
    }

    // ==================== 隐私风险评估（#44） ====================

    /**
     * 隐私风险评估结果
     */
    data class PrivacyRiskAssessment(
        val overallRiskLevel: RiskLevel,
        val riskScore: Float,
        val riskFactors: List<PrivacyRiskFactor>,
        val recommendations: List<String>
    )

    data class PrivacyRiskFactor(
        val name: String,
        val description: String,
        val severity: RiskLevel,
        val mitigation: String
    )

    /**
     * 评估隐私风险
     */
    fun assessPrivacyRisk(): PrivacyRiskAssessment {
        val riskFactors = mutableListOf<PrivacyRiskFactor>()

        // 1) 敏感权限授权面（仅统计授权，不声称实际调用）
        val sensitivePermissions = checkPermissionGrants()
        val appsWithSensitivePermissions = sensitivePermissions.map { it.packageName }.distinct().size
        if (sensitivePermissions.isNotEmpty()) {
            val severity = when {
                sensitivePermissions.size >= 8 -> RiskLevel.HIGH
                sensitivePermissions.size >= 4 -> RiskLevel.MEDIUM
                else -> RiskLevel.LOW
            }
            riskFactors.add(PrivacyRiskFactor(
                name = "敏感权限授权",
                description = "$appsWithSensitivePermissions 个应用共获授 ${sensitivePermissions.size} 项敏感权限",
                severity = severity,
                mitigation = "建议审查并撤销不必要的敏感权限授权"
            ))
        }

        // 2) 存储加密状态（读取真实设备策略）
        val encryptionStatus = runCatching { devicePolicyManager.storageEncryptionStatus }
            .getOrDefault(DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED)
        val encrypted = encryptionStatus == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ||
            encryptionStatus == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER
        if (!encrypted) {
            riskFactors.add(PrivacyRiskFactor(
                name = "数据存储安全",
                description = "设备存储未加密（状态码 $encryptionStatus）",
                severity = RiskLevel.HIGH,
                mitigation = "建议启用设备加密"
            ))
        }

        // 3) 屏幕锁（影响本地数据可访问性）
        val secure = runCatching {
            (context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure
        }.getOrDefault(false)
        if (!encrypted && !secure) {
            riskFactors.add(PrivacyRiskFactor(
                name = "设备锁屏",
                description = "设备未设置锁屏密码",
                severity = RiskLevel.HIGH,
                mitigation = "建议设置锁屏密码或生物识别"
            ))
        }

        // 3) 明文数据落盘检查（真实扫描应用私有目录）
        val plaintextFiles = findPlaintextSensitiveFiles()
        if (plaintextFiles.isNotEmpty()) {
            riskFactors.add(PrivacyRiskFactor(
                name = "本地明文数据",
                description = "发现 ${plaintextFiles.size} 个疑似未加密的敏感数据文件",
                severity = RiskLevel.MEDIUM,
                mitigation = "建议对本地敏感数据启用加密存储"
            ))
        }

        val riskScore = if (riskFactors.isEmpty()) 0f else riskFactors.sumOf {
            when (it.severity) {
                RiskLevel.LOW -> 1
                RiskLevel.MEDIUM -> 2
                RiskLevel.HIGH -> 3
                RiskLevel.CRITICAL -> 4
            }
        }.toFloat() / (riskFactors.size * 4).coerceAtLeast(1)

        val overallRisk = when {
            riskScore > 0.75f -> RiskLevel.CRITICAL
            riskScore > 0.5f -> RiskLevel.HIGH
            riskScore > 0.25f -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        return PrivacyRiskAssessment(
            overallRiskLevel = overallRisk,
            riskScore = riskScore,
            riskFactors = riskFactors,
            recommendations = riskFactors.map { it.mitigation }
        )
    }

    /** 扫描应用私有目录，统计疑似明文存储的敏感数据文件。 */
    private fun findPlaintextSensitiveFiles(): List<File> {
        val roots = listOf(context.filesDir, context.getExternalFilesDir(null))
        val keywords = listOf("medical", "backup", "memory", "history", "profile", "chat")
        return roots.filterNotNull().flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in setOf("json", "txt", "db", "log") }
                .filter { file -> keywords.any { file.name.lowercase().contains(it) } }
                .toList()
        }
    }

    // ==================== 安全风险评估（#45） ====================

    /**
     * 安全风险评估结果
     */
    data class SecurityRiskAssessment(
        val overallRiskLevel: RiskLevel,
        val riskScore: Float,
        val threatVectors: List<ThreatVector>,
        val vulnerabilities: List<Vulnerability>,
        val recommendations: List<String>
    )

    data class ThreatVector(
        val name: String,
        val description: String,
        val likelihood: Float,
        val impact: Float,
        val riskLevel: RiskLevel
    )

    data class Vulnerability(
        val name: String,
        val description: String,
        val severity: RiskLevel,
        val remediation: String
    )

    /**
     * 评估安全风险
     */
    fun assessSecurityRisk(): SecurityRiskAssessment {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val secure = runCatching { keyguard.isDeviceSecure }.getOrDefault(false)
        val adbEnabled = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)
        val devOptions = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        }.getOrDefault(false)
        val rooted = isRooted()
        val adminApps = runCatching { devicePolicyManager.activeAdmins?.size ?: 0 }.getOrDefault(0)
        val unknownSources = runCatching {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        }.getOrDefault(false)
        val patchLevel = android.os.Build.VERSION.SECURITY_PATCH ?: ""

        fun level(score: Float) = when {
            score >= 0.5f -> RiskLevel.HIGH
            score >= 0.3f -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        val threatVectors = mutableListOf(
            ThreatVector(
                name = "恶意软件",
                description = if (unknownSources) "允许安装未知来源应用，恶意软件风险上升" else "仅允许可信来源安装",
                likelihood = if (unknownSources) 0.5f else 0.15f,
                impact = 0.8f,
                riskLevel = level((if (unknownSources) 0.5f else 0.15f) * 0.8f)
            ),
            ThreatVector(
                name = "网络攻击",
                description = if (adbEnabled) "USB 调试已开启，可被物理接入调试" else "USB 调试关闭",
                likelihood = if (adbEnabled) 0.4f else 0.15f,
                impact = 0.7f,
                riskLevel = level((if (adbEnabled) 0.4f else 0.15f) * 0.7f)
            ),
            ThreatVector(
                name = "物理访问",
                description = if (secure) "已设置锁屏，具备基础防护" else "未设置锁屏，物理访问风险高",
                likelihood = if (secure) 0.3f else 0.7f,
                impact = 0.6f,
                riskLevel = level((if (secure) 0.3f else 0.7f) * 0.6f)
            ),
            ThreatVector(
                name = "Root / 篡改",
                description = if (rooted) "检测到 root 痕迹，系统完整性受损" else "未检测到 root 痕迹",
                likelihood = if (rooted) 0.7f else 0.1f,
                impact = 0.9f,
                riskLevel = level((if (rooted) 0.7f else 0.1f) * 0.9f)
            )
        )

        val vulnerabilities = mutableListOf<Vulnerability>()
        if (!secure) {
            vulnerabilities.add(Vulnerability("无锁屏密码", "设备未设置锁屏密码", RiskLevel.HIGH, "设置强密码并启用生物识别"))
        }
        if (adbEnabled) {
            vulnerabilities.add(Vulnerability("USB 调试开启", "ADB 已启用，存在物理接入风险", RiskLevel.MEDIUM, "在不需要时关闭 USB 调试"))
        }
        if (devOptions) {
            vulnerabilities.add(Vulnerability("开发者选项开启", "开发者选项处于开启状态", RiskLevel.LOW, "日常使用中关闭开发者选项"))
        }
        if (rooted) {
            vulnerabilities.add(Vulnerability("设备已 Root", "检测到 su 或 Magisk 痕迹", RiskLevel.CRITICAL, "恢复官方系统以保证完整性"))
        }
        if (adminApps > 1) {
            vulnerabilities.add(Vulnerability("多个设备管理器", "存在 $adminApps 个活动设备管理器应用", RiskLevel.MEDIUM, "审查并移除不需要的设备管理器"))
        }
        if (patchLevel.isBlank()) {
            vulnerabilities.add(Vulnerability("系统更新状态未知", "无法读取安全补丁级别", RiskLevel.MEDIUM, "手动检查系统更新"))
        }

        val riskScore = threatVectors.map { it.likelihood * it.impact }.average().toFloat()

        val overallRisk = when {
            riskScore > 0.5f -> RiskLevel.HIGH
            riskScore > 0.3f -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        return SecurityRiskAssessment(
            overallRiskLevel = overallRisk,
            riskScore = riskScore,
            threatVectors = threatVectors,
            vulnerabilities = vulnerabilities,
            recommendations = vulnerabilities.map { it.remediation }.ifEmpty { listOf("当前安全配置良好，保持系统更新") }
        )
    }

    /** 检测设备是否被 root（检查常见 su 二进制与 Magisk 路径）。 */
    private fun isRooted(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/app/Superuser.apk", "/data/local/xbin/su", "/data/local/bin/su",
            "/magisk/.core/bin/su", "/system/bin/magisk"
        )
        if (paths.any { File(it).exists() }) return true
        return runCatching {
            ProcessBuilder("which", "su").start().waitFor() == 0
        }.getOrDefault(false)
    }

    // ==================== 防御部署（#47） ====================

    /**
     * 部署安全防御策略
     */
    fun deployDefenseStrategy(): DefenseDeploymentResult {
        val deployed = mutableListOf<String>()
        val failed = mutableListOf<String>()

        val isAdmin = runCatching { devicePolicyManager.isAdminActive(adminComponent) }.getOrDefault(false)
        if (isAdmin) {
            runCatching {
                devicePolicyManager.setPasswordQuality(
                    adminComponent,
                    DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                )
            }.onSuccess { deployed += "设置强密码策略" }.onFailure { failed += "设置强密码策略" }

            runCatching {
                devicePolicyManager.setMaximumFailedPasswordsForWipe(adminComponent, 10)
            }.onSuccess { deployed += "配置连续失败自动擦除（10 次）" }.onFailure { failed += "配置自动数据擦除" }

            runCatching {
                devicePolicyManager.setMaximumTimeToLock(adminComponent, 30_000L)
            }.onSuccess { deployed += "配置 30 秒自动锁屏" }.onFailure { failed += "配置自动锁屏" }
        } else {
            failed += "设备管理器未激活，系统级策略不可用"
        }


        return DefenseDeploymentResult(
            deployedStrategies = deployed,
            isSuccessful = deployed.isNotEmpty() && failed.isEmpty(),
            message = if (failed.isEmpty()) "全部防御策略已部署" else "部分策略未生效：${failed.joinToString("；")}"
        )
    }

    data class DefenseDeploymentResult(
        val deployedStrategies: List<String>,
        val isSuccessful: Boolean,
        val message: String
    )

    // ==================== 安全协议执行（#48） ====================

    /**
     * 执行安全协议
     */
    fun executeSecurityProtocol(protocol: SecurityProtocol): Boolean {
        return when (protocol) {
            SecurityProtocol.LOCKDOWN -> executeLockdownProtocol()
            SecurityProtocol.DATA_WIPE -> executeDataWipeProtocol()
        }
    }

    private fun executeLockdownProtocol(): Boolean {
        // 立即锁定屏幕（需设备管理器已激活）
        return lockDevice("安全协议启动：设备已锁定")
    }

    private fun executeDataWipeProtocol(): Boolean {
        // 仅在设备管理器激活时执行真实擦除
        return runCatching {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.wipeData(0)
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    // ==================== 安全事件响应（#42） ====================

    data class SecurityResponse(
        val actionTaken: String,
        val success: Boolean,
        val message: String
    )
    enum class ResponseAction { NONE, LOCK, LOCKDOWN, WIPE }

    private fun toRiskLevel(severity: SecurityEvent.Severity): RiskLevel = when (severity) {
        SecurityEvent.Severity.LOW -> RiskLevel.LOW
        SecurityEvent.Severity.MEDIUM -> RiskLevel.MEDIUM
        SecurityEvent.Severity.HIGH -> RiskLevel.HIGH
        SecurityEvent.Severity.CRITICAL -> RiskLevel.CRITICAL
    }

    /**
     * 响应安全事件：根据严重级别执行锁定 / 锁定协议；高危擦除需用户在“擦除数据”处二次确认，
     * 此处拒绝自动擦除并返回失败，绝不伪装成功。
     */
    fun respondToSecurityEvent(event: SecurityEvent): SecurityResponse {
        return when (decideResponse(toRiskLevel(event.severity))) {
            ResponseAction.NONE -> SecurityResponse(
                actionTaken = "无操作", success = true,
                message = "事件级别较低，未触发自动响应"
            )
            ResponseAction.LOCK -> {
                val ok = lockDevice("安全响应：${event.description}")
                SecurityResponse("锁定设备", ok, if (ok) "设备已锁定" else ADMIN_INACTIVE)
            }
            ResponseAction.LOCKDOWN -> {
                val ok = executeSecurityProtocol(SecurityProtocol.LOCKDOWN)
                SecurityResponse("锁定协议", ok, if (ok) "已执行锁定协议" else ADMIN_INACTIVE)
            }
            ResponseAction.WIPE -> SecurityResponse(
                actionTaken = "擦除数据", success = false,
                message = "数据擦除需用户二次确认，请使用“擦除数据”入口"
            )
        }
    }

    enum class SecurityProtocol {
        LOCKDOWN, DATA_WIPE
    }

    companion object {
        const val ADMIN_INACTIVE = "设备管理器未激活"
        const val ADMIN_ACTIVATION_ACTION = "android.app.action.ADD_DEVICE_ADMIN"

        fun lockResult(adminActive: Boolean): Boolean = adminActive

        fun wipeResult(adminActive: Boolean): Boolean = adminActive

        /** 纯逻辑：根据事件严重级别决定响应动作，便于单测。 */
        fun decideResponse(severity: RiskLevel): ResponseAction = when (severity) {
            RiskLevel.LOW -> ResponseAction.NONE
            RiskLevel.MEDIUM -> ResponseAction.LOCK
            RiskLevel.HIGH -> ResponseAction.LOCKDOWN
            RiskLevel.CRITICAL -> ResponseAction.WIPE
        }

        fun adminActivationIntent(): android.content.Intent {
            return android.content.Intent(ADMIN_ACTIVATION_ACTION)
        }
    }
}

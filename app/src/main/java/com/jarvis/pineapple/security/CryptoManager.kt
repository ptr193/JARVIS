package com.jarvis.pineapple.security

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security

/**
 * 加密管理器（真实 AES-256-GCM 实现）。
 *
 * 使用 BouncyCastle 实现 AES-256-GCM 加密/解密，
 * 符合产品规格书中的数据加密要求（#26, #27）。
 *
 * 功能：
 * - AES-256-GCM 加密
 * - AES-256-GCM 解密
 * - 随机 IV 生成
 * - SHA-256 密钥派生
 * - HMAC-SHA256 完整性验证
 */
class CryptoManager(private val context: Context) {

    init {
        // 添加 BouncyCastle 提供者
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    companion object {
        private const val AES_KEY_SIZE = 256
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    // ==================== 密钥派生 ====================

    /**
     * 从密码派生 AES-256 密钥
     */
    fun deriveKey(password: String, salt: ByteArray? = null): Pair<ByteArray, ByteArray> {
        val actualSalt = salt ?: ByteArray(16).also { SecureRandom().nextBytes(it) }

        // 使用 SHA-256 迭代派生密钥（AES_KEY_SIZE 位 = 32 字节）
        val keyBytes = ByteArray(AES_KEY_SIZE / 8)
        var hash = (password + Base64.encodeToString(actualSalt, Base64.NO_WRAP)).toByteArray()

        // 迭代哈希 10000 次
        repeat(10000) {
            hash = MessageDigest.getInstance("SHA-256").digest(hash)
        }

        System.arraycopy(hash, 0, keyBytes, 0, keyBytes.size)
        return Pair(keyBytes, actualSalt)
    }

    // ==================== AES-256-GCM 加密 ====================

    /**
     * 加密数据
     *
     * @param plainText 明文
     * @param password 密码
     * @return Base64 编码的密文（包含 salt + iv + ciphertext + tag）
     */
    fun encrypt(plainText: String, password: String): String {
        return try {
            val (key, salt) = deriveKey(password)
            val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }

            val cipher = GCMBlockCipher(AESEngine())
            val parameters = AEADParameters(KeyParameter(key), GCM_TAG_LENGTH, iv)
            cipher.init(true, parameters)

            val plainBytes = plainText.toByteArray(Charsets.UTF_8)
            val cipherText = ByteArray(cipher.getOutputSize(plainBytes.size))
            val len = cipher.processBytes(plainBytes, 0, plainBytes.size, cipherText, 0)
            cipher.doFinal(cipherText, len)

            // 组合: salt + iv + ciphertext
            val combined = salt + iv + cipherText
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw EncryptionException("加密失败: ${e.message}", e)
        }
    }

    // ==================== AES-256-GCM 解密 ====================

    /**
     * 解密数据
     *
     * @param encryptedBase64 Base64 编码的密文
     * @param password 密码
     * @return 明文
     */
    fun decrypt(encryptedBase64: String, password: String): String {
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)

            // 提取 salt, iv, ciphertext
            val salt = combined.copyOfRange(0, 16)
            val iv = combined.copyOfRange(16, 16 + GCM_IV_LENGTH)
            val cipherText = combined.copyOfRange(16 + GCM_IV_LENGTH, combined.size)

            val (key, _) = deriveKey(password, salt)

            val cipher = GCMBlockCipher(AESEngine())
            val parameters = AEADParameters(KeyParameter(key), GCM_TAG_LENGTH, iv)
            cipher.init(false, parameters)

            val plainBytes = ByteArray(cipher.getOutputSize(cipherText.size))
            val len = cipher.processBytes(cipherText, 0, cipherText.size, plainBytes, 0)
            val finalLen = cipher.doFinal(plainBytes, len)

            String(plainBytes, 0, len + finalLen, Charsets.UTF_8)
        } catch (e: Exception) {
            throw EncryptionException("解密失败: ${e.message}", e)
        }
    }

    // ==================== 加密工具方法 ====================

    /**
     * 生成随机密钥
     */
    fun generateRandomKey(): String {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    /**
     * 使用密钥加密
     */
    fun encryptWithKey(plainText: String, keyBase64: String): String {
        return try {
            val key = Base64.decode(keyBase64, Base64.NO_WRAP)
            val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }

            val cipher = GCMBlockCipher(AESEngine())
            val parameters = AEADParameters(KeyParameter(key), GCM_TAG_LENGTH, iv)
            cipher.init(true, parameters)

            val plainBytes = plainText.toByteArray(Charsets.UTF_8)
            val cipherText = ByteArray(cipher.getOutputSize(plainBytes.size))
            val len = cipher.processBytes(plainBytes, 0, plainBytes.size, cipherText, 0)
            cipher.doFinal(cipherText, len)

            val combined = iv + cipherText
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw EncryptionException("加密失败: ${e.message}", e)
        }
    }

    /**
     * 使用密钥解密
     */
    fun decryptWithKey(encryptedBase64: String, keyBase64: String): String {
        return try {
            val key = Base64.decode(keyBase64, Base64.NO_WRAP)
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val cipher = GCMBlockCipher(AESEngine())
            val parameters = AEADParameters(KeyParameter(key), GCM_TAG_LENGTH, iv)
            cipher.init(false, parameters)

            val plainBytes = ByteArray(cipher.getOutputSize(cipherText.size))
            val len = cipher.processBytes(cipherText, 0, cipherText.size, plainBytes, 0)
            val finalLen = cipher.doFinal(plainBytes, len)

            String(plainBytes, 0, len + finalLen, Charsets.UTF_8)
        } catch (e: Exception) {
            throw EncryptionException("解密失败: ${e.message}", e)
        }
    }

    // ==================== 哈希与签名 ====================

    /**
     * 计算 SHA-256 哈希
     */
    fun sha256(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算 HMAC-SHA256
     */
    fun hmacSha256(data: String, key: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretKey = javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val hashBytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    // ==================== 安全随机 ====================

    /**
     * 生成安全随机字符串
     */
    fun generateSecureRandomString(length: Int = 32): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return (1..length)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }

    /**
     * 生成安全随机字节
     */
    fun generateSecureRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    /**
     * 验证密码强度
     */
    fun validatePasswordStrength(password: String): PasswordStrength {
        var score = 0

        if (password.length >= 8) score++
        if (password.length >= 12) score++
        if (password.any { it.isUpperCase() }) score++
        if (password.any { it.isLowerCase() }) score++
        if (password.any { it.isDigit() }) score++
        if (password.any { !it.isLetterOrDigit() }) score++

        return when {
            score >= 5 -> PasswordStrength.STRONG
            score >= 3 -> PasswordStrength.MEDIUM
            else -> PasswordStrength.WEAK
        }
    }

    enum class PasswordStrength {
        WEAK, MEDIUM, STRONG
    }

    // ==================== 加密异常 ====================

    /**
     * 加密异常
     */
    class EncryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
}

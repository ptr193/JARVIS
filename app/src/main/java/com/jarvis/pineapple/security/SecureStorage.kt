package com.jarvis.pineapple.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 加密存储工具：API Key 等敏感信息使用 Android Keystore（AES-256-GCM）加密后存储。
 *
 * 实现方式：
 * - 主密钥由 Android Keystore 生成并保管（无法导出，随应用卸载销毁）。
 * - 每个值使用独立的随机 IV，以 AES/GCM/NoPadding 加密。
 * - 密文以 Base64（IV + 密文）写入普通 SharedPreferences。
 *
 * 不依赖任何第三方加密库，密钥材料不会以明文落盘。
 */
class SecureStorage(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun keystore(): KeyStore {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        return ks
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = keystore()
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun putString(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(key, payload).apply()
    }

    fun getString(key: String, default: String = ""): String {
        val payload = prefs.getString(key, null) ?: return default
        return try {
            val parts = payload.split(":", limit = 2)
            if (parts.size != 2) return default
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            default
        }
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** 清空全部加密存储项。 */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "jarvis_secure_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "jarvis_secure_prefs"
    }
}

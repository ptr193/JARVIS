package com.jarvis.pineapple.cloud

import android.util.Log
import com.jarvis.pineapple.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ConnectedDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType,
    val isOnline: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis(),
    val isPaired: Boolean = false
)

enum class DeviceType { PHONE, TABLET, COMPUTER, SMART_HOME, OTHER }

data class SyncStatus(
    val isCloudEnabled: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncTime: Long = 0,
    val pendingCommands: Int = 0,
    val connectedDevices: List<ConnectedDevice> = emptyList()
)

data class RemoteCommand(
    val commandId: String,
    val fromDevice: String,
    val action: String,
    val params: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class SyncData(
    val deviceId: String,
    val timestamp: Long,
    val conversationState: String? = null,
    val memoryData: String? = null,
    val settings: String? = null,
    val reminders: String? = null
)

@Serializable
data class DeviceInfoResponse(val devices: List<DeviceDto> = emptyList())

@Serializable
data class DeviceDto(val id: String, val name: String, val type: String, val online: Boolean)

@Serializable
data class PairingRequest(val pairingCode: String, val deviceName: String)

@Serializable
data class PairingResponse(val success: Boolean, val message: String)

@Serializable
data class RemoteCommandRequest(val action: String, val params: Map<String, String> = emptyMap())

/** 账号登录请求（#134 无处不在的接入）。 */
@Serializable
data class AccountLoginRequest(val account: String, val password: String)

/** 账号登录响应。 */
@Serializable
data class AccountLoginResponse(
    val success: Boolean = false,
    val token: String? = null,
    val userId: String? = null,
    val message: String = ""
)

/**
 * 云端接口与多设备互联管理器（REQ-12）。
 * 当 serverUrl 为空时，云端功能自动禁用，所有方法优雅降级。
 */
class CloudSyncManager(
    private val serverUrl: String = BuildConfig.CLOUD_SYNC_BASE_URL
) {
    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    private val _syncStatus = MutableStateFlow(SyncStatus(isCloudEnabled = serverUrl.isNotBlank()))
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    /** 云端功能是否可用 */
    fun isCloudAvailable(): Boolean = serverUrl.isNotBlank()

    /** 启用/禁用云端同步 */
    fun setCloudEnabled(enabled: Boolean) {
        _syncStatus.value = _syncStatus.value.copy(isCloudEnabled = enabled && serverUrl.isNotBlank())
    }

    // ==================== 账号与多设备接入（#134） ====================

    private val _accountToken = MutableStateFlow<String?>(null)
    /** 当前登录令牌；为 null 表示未登录。 */
    val accountToken: StateFlow<String?> = _accountToken.asStateFlow()

    private val _accountName = MutableStateFlow<String?>(null)
    val accountName: StateFlow<String?> = _accountName.asStateFlow()

    fun isLoggedIn(): Boolean = _accountToken.value != null

    /** 恢复已持久化的登录态（由 UI 从 SharedPreferences 读出后调用）。 */
    fun restoreSession(account: String, token: String) {
        _accountName.value = account
        _accountToken.value = token
    }

    /** 账号密码登录，成功后保存令牌。 */
    suspend fun login(account: String, password: String): Boolean {
        if (!isCloudAvailable()) return false
        return try {
            val response: AccountLoginResponse = httpClient.post("$serverUrl/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(AccountLoginRequest(account, password))
            }.body()
            if (response.success && response.token != null) {
                _accountName.value = account
                _accountToken.value = response.token
            }
            response.success
        } catch (e: Exception) {
            Log.w("CloudSyncManager", "login failed", e)
            false
        }
    }

    /** 注册账号并绑定当前设备。 */
    suspend fun register(account: String, password: String, deviceName: String): Boolean {
        if (!isCloudAvailable()) return false
        return try {
            val response: AccountLoginResponse = httpClient.post("$serverUrl/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(AccountLoginRequest(account, password))
                header("X-Device-Name", deviceName)
            }.body()
            if (response.success && response.token != null) {
                _accountName.value = account
                _accountToken.value = response.token
            }
            response.success
        } catch (e: Exception) {
            Log.w("CloudSyncManager", "register failed", e)
            false
        }
    }

    /** 退出登录，清除内存中的令牌。 */
    fun logout() {
        _accountName.value = null
        _accountToken.value = null
    }

    /** 为请求附加 Bearer 令牌（未登录时不附加）。 */
    private fun HttpRequestBuilder.authHeader() {
        _accountToken.value?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    /** 发现设备（云端不可用时返回空列表） */
    suspend fun discoverDevices(): List<ConnectedDevice> {
        if (!isCloudAvailable()) return emptyList()
        return try {
            val response: DeviceInfoResponse = httpClient.get("$serverUrl/api/devices") { authHeader() }.body()
            response.devices.map {
                ConnectedDevice(
                    deviceId = it.id,
                    deviceName = it.name,
                    deviceType = runCatching { DeviceType.valueOf(it.type.uppercase()) }.getOrDefault(DeviceType.OTHER),
                    isOnline = it.online,
                    isPaired = true
                )
            }
        } catch (e: Exception) {
            Log.w("CloudSyncManager", "discoverDevices failed", e)
            emptyList()
        }
    }

    /** 配对设备（云端不可用时返回失败） */
    suspend fun pairDevice(pairingCode: String, deviceName: String): Boolean {
        if (!isCloudAvailable()) return false
        return try {
            val response: PairingResponse = httpClient.post("$serverUrl/api/devices/pair") {
                authHeader()
                contentType(ContentType.Application.Json)
                setBody(PairingRequest(pairingCode, deviceName))
            }.body()
            response.success
        } catch (e: Exception) {
            Log.w("CloudSyncManager", "pairDevice failed", e)
            false
        }
    }

    /** 同步对话状态 */
    suspend fun syncConversationState(state: String): Boolean {
        if (!isCloudAvailable()) return false
        return try {
            httpClient.post("$serverUrl/api/sync/conversation") {
                authHeader()
                contentType(ContentType.Application.Json)
                setBody(SyncData(deviceId = getDeviceId(), timestamp = System.currentTimeMillis(), conversationState = state))
            }
            _syncStatus.value = _syncStatus.value.copy(lastSyncTime = System.currentTimeMillis())
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 同步记忆数据（加密后） */
    suspend fun syncMemory(encryptedData: String): Boolean {
        if (!isCloudAvailable()) return false
        return try {
            httpClient.post("$serverUrl/api/sync/memory") {
                authHeader()
                contentType(ContentType.Application.Json)
                setBody(SyncData(deviceId = getDeviceId(), timestamp = System.currentTimeMillis(), memoryData = encryptedData))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 同步设置 */
    suspend fun syncSettings(settingsJson: String): Boolean {
        if (!isCloudAvailable()) return false
        return try {
            httpClient.post("$serverUrl/api/sync/settings") {
                authHeader()
                contentType(ContentType.Application.Json)
                setBody(SyncData(deviceId = getDeviceId(), timestamp = System.currentTimeMillis(), settings = settingsJson))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 同步提醒 */
    suspend fun syncReminders(remindersJson: String): Boolean {
        if (!isCloudAvailable()) return false
        return try {
            httpClient.post("$serverUrl/api/sync/reminders") {
                authHeader()
                contentType(ContentType.Application.Json)
                setBody(SyncData(deviceId = getDeviceId(), timestamp = System.currentTimeMillis(), reminders = remindersJson))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 发送远程指令 */
    suspend fun sendRemoteCommand(targetDeviceId: String, action: String, params: Map<String, String>): Boolean {
        if (!isCloudAvailable()) return false
        return try {
            httpClient.post("$serverUrl/api/commands") {
                authHeader()
                contentType(ContentType.Application.Json)
                setBody(RemoteCommandRequest(action, params))
                header("X-Target-Device", targetDeviceId)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getDeviceId(): String = "android_${android.os.Build.FINGERPRINT.hashCode().toUInt()}"
}

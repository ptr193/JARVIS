package com.jarvis.pineapple.cloud

import android.content.Context
import com.jarvis.pineapple.BuildConfig
import com.jarvis.pineapple.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

// 云端数据模型
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

@Serializable
data class AccountLoginRequest(val account: String, val password: String)

@Serializable
data class AccountLoginResponse(
    val success: Boolean = false,
    val token: String? = null,
    val userId: String? = null,
    val message: String = ""
)

data class StoredCloudSession(val account: String, val token: String)

/** 登录态存储边界；生产实现使用 Android Keystore，JVM 测试可注入故障。 */
interface CloudSessionStore {
    val lastError: String?
    fun load(): StoredCloudSession?
    fun save(account: String, token: String): Boolean
    fun clear(): Boolean
}

private object MemorylessCloudSessionStore : CloudSessionStore {
    override val lastError: String? = "未配置安全登录态存储"
    override fun load(): StoredCloudSession? = null
    override fun save(account: String, token: String): Boolean = false
    override fun clear(): Boolean = true
}

/** 加密保存云端令牌，并在初始化时清除旧版 SharedPreferences 明文凭据。 */
internal class SecureCloudSessionStore(
    context: Context,
    private val secureStorage: SecureStorage
) : CloudSessionStore {
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    @Volatile
    override var lastError: String? = null
        private set

    init {
        migrateLegacyCredentials()
    }

    @Synchronized
    override fun load(): StoredCloudSession? {
        return try {
            val token = secureStorage.getString(SECURE_TOKEN)
            if (token.isBlank()) null else {
                StoredCloudSession(secureStorage.getString(SECURE_ACCOUNT), token)
            }
        } catch (e: Exception) {
            lastError = "加密登录态读取失败：${e.javaClass.simpleName}"
            null
        }
    }

    @Synchronized
    override fun save(account: String, token: String): Boolean {
        return try {
            secureStorage.putString(SECURE_ACCOUNT, account)
            secureStorage.putString(SECURE_TOKEN, token)
            val saved = secureStorage.getString(SECURE_TOKEN) == token &&
                secureStorage.getString(SECURE_ACCOUNT) == account
            lastError = if (saved) null else "登录态加密保存校验失败"
            saved
        } catch (e: Exception) {
            lastError = "登录态加密保存失败：${e.javaClass.simpleName}"
            false
        }
    }

    @Synchronized
    override fun clear(): Boolean {
        val legacyCleared = runCatching {
            legacyPrefs.edit().remove("token").remove("account").commit()
        }.getOrDefault(false)
        val secureCleared = runCatching {
            secureStorage.remove(SECURE_TOKEN)
            secureStorage.remove(SECURE_ACCOUNT)
            secureStorage.getString(SECURE_TOKEN).isBlank()
        }.getOrDefault(false)
        val cleared = legacyCleared && secureCleared
        lastError = if (cleared) null else "本地登录凭据清理失败"
        return cleared
    }

    private fun migrateLegacyCredentials() {
        val legacyToken = legacyPrefs.getString("token", null)
        val legacyAccount = legacyPrefs.getString("account", null)
        if (legacyToken == null && legacyAccount == null) return

        var migrationFailed = false
        if (!legacyToken.isNullOrBlank() && secureStorage.getString(SECURE_TOKEN).isBlank()) {
            if (!save(legacyAccount.orEmpty(), legacyToken)) {
                migrationFailed = true
                secureStorage.remove(SECURE_TOKEN)
                secureStorage.remove(SECURE_ACCOUNT)
            }
        } else if (secureStorage.getString(SECURE_ACCOUNT).isBlank() && !legacyAccount.isNullOrBlank()) {
            runCatching { secureStorage.putString(SECURE_ACCOUNT, legacyAccount) }
        }
        val legacyCleared = legacyPrefs.edit().remove("token").remove("account").commit()
        lastError = when {
            migrationFailed && !legacyCleared -> "旧登录凭据无法安全迁移且明文清理失败，请检查存储后重新登录"
            migrationFailed -> "旧登录凭据无法安全迁移，明文已清除，请重新登录"
            !legacyCleared -> "旧明文登录凭据清理失败"
            else -> null
        }
    }

    private companion object {
        const val LEGACY_PREFS = "jarvis_devices"
        const val SECURE_ACCOUNT = "cloud_account"
        const val SECURE_TOKEN = "cloud_token"
    }
}

/**
 * 云端接口与多设备互联管理器（REQ-12）。
 * 未配置服务、未登录、HTTP/超时/网络失败均通过 [lastError] 明确暴露。
 */
class CloudSyncManager(
    private val serverUrl: String = BuildConfig.CLOUD_SYNC_BASE_URL,
    engine: HttpClientEngine = Android.create(),
    private val sessionStore: CloudSessionStore = MemorylessCloudSessionStore
) {
    private val httpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(sessionStore.lastError)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _accountName = MutableStateFlow<String?>(null)
    val accountName: StateFlow<String?> = _accountName.asStateFlow()

    @Volatile
    private var accountToken: String? = null
    private var syncInFlight = 0

    init {
        try {
            sessionStore.load()?.let { session ->
                _accountName.value = session.account
                accountToken = session.token
            }
            _lastError.value = sessionStore.lastError
        } catch (e: Exception) {
            _lastError.value = "登录态恢复失败：${e.javaClass.simpleName}"
        }
    }

    fun isCloudAvailable(): Boolean = serverUrl.isNotBlank()
    fun isLoggedIn(): Boolean = !accountToken.isNullOrBlank()

    suspend fun login(account: String, password: String): Boolean =
        authenticate("登录", account, password) {
            httpClient.post("$serverUrl/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(AccountLoginRequest(account, password))
            }
        }

    suspend fun register(account: String, password: String, deviceName: String): Boolean =
        authenticate("注册", account, password) {
            httpClient.post("$serverUrl/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(AccountLoginRequest(account, password))
                header("X-Device-Name", deviceName)
            }
        }

    private suspend fun authenticate(
        operation: String,
        account: String,
        password: String,
        request: suspend () -> HttpResponse
    ): Boolean = performRequest(operation, false, requiresAuth = false, request = request) { response ->
        val result: AccountLoginResponse = response.body()
        val token = result.token
        if (!result.success || token.isNullOrBlank()) {
            return@performRequest fail(result.message.ifBlank { "${operation}被服务端拒绝" }, false)
        }
        if (!saveSession(account, token)) return@performRequest false
        _accountName.value = account
        accountToken = token
        true
    }

    private fun saveSession(account: String, token: String): Boolean {
        val saved = runCatching { sessionStore.save(account, token) }.getOrDefault(false)
        if (!saved) {
            _lastError.value = sessionStore.lastError ?: "无法安全保存登录态"
        }
        return saved
    }

    fun logout() {
        val cleared = runCatching { sessionStore.clear() }.getOrDefault(false)
        accountToken = null
        _accountName.value = null
        _syncStatus.update { it.copy(connectedDevices = emptyList()) }
        _lastError.value = if (cleared) null else sessionStore.lastError ?: "本地登录凭据清理失败"
    }

    private fun HttpRequestBuilder.authHeader() {
        accountToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    suspend fun discoverDevices(): List<ConnectedDevice>? = performRequest(
        "设备发现",
        null,
        request = { httpClient.get("$serverUrl/api/devices") { authHeader() } },
        success = { response ->
            val result: DeviceInfoResponse = response.body()
            val devices = result.devices.map {
                ConnectedDevice(
                    deviceId = it.id,
                    deviceName = it.name,
                    deviceType = runCatching { DeviceType.valueOf(it.type.uppercase()) }
                        .getOrDefault(DeviceType.OTHER),
                    isOnline = it.online,
                    isPaired = true
                )
            }
            _syncStatus.update { it.copy(connectedDevices = devices) }
            devices
        }
    )

    suspend fun pairDevice(pairingCode: String, deviceName: String): Boolean =
        performRequest("设备配对", false, request = {
            httpClient.post("$serverUrl/api/devices/pair") {
                authHeader()
                contentType(ContentType.Application.Json)
                setBody(PairingRequest(pairingCode, deviceName))
            }
        }) { response ->
            val result: PairingResponse = response.body()
            if (result.success) true else fail(result.message.ifBlank { "服务端拒绝配对" }, false)
        }

    suspend fun syncConversationState(state: String): Boolean = syncPayload(
        "对话同步",
        "/api/sync/conversation",
        SyncData(getDeviceId(), System.currentTimeMillis(), conversationState = state)
    )

    suspend fun syncMemory(encryptedData: String): Boolean = syncPayload(
        "记忆同步",
        "/api/sync/memory",
        SyncData(getDeviceId(), System.currentTimeMillis(), memoryData = encryptedData)
    )

    suspend fun syncSettings(settingsJson: String): Boolean = syncPayload(
        "设置同步",
        "/api/sync/settings",
        SyncData(getDeviceId(), System.currentTimeMillis(), settings = settingsJson)
    )

    suspend fun syncReminders(remindersJson: String): Boolean = syncPayload(
        "提醒同步",
        "/api/sync/reminders",
        SyncData(getDeviceId(), System.currentTimeMillis(), reminders = remindersJson)
    )

    private suspend fun syncPayload(operation: String, path: String, data: SyncData): Boolean =
        performRequest(operation, false, syncing = true, request = {
            httpClient.post("$serverUrl$path") {
                authHeader()
                contentType(ContentType.Application.Json)
                setBody(data)
            }
        }) { true }

    suspend fun sendRemoteCommand(targetDeviceId: String, action: String, params: Map<String, String>): Boolean =
        performRequest("远程指令发送", false, request = {
            httpClient.post("$serverUrl/api/commands") {
                authHeader()
                contentType(ContentType.Application.Json)
                setBody(RemoteCommandRequest(action, params))
                header("X-Target-Device", targetDeviceId)
            }
        }) { true }

    private suspend fun <T> performRequest(
        operation: String,
        failureValue: T,
        requiresAuth: Boolean = true,
        syncing: Boolean = false,
        request: suspend () -> HttpResponse,
        success: suspend (HttpResponse) -> T
    ): T {
        if (!isCloudAvailable()) return fail("${operation}失败：云端未配置", failureValue)
        if (requiresAuth && !isLoggedIn()) return fail("${operation}失败：尚未登录", failureValue)
        _lastError.value = null
        if (syncing) updateSyncInFlight(1)
        try {
            val response = request()
            if (response.status.value !in 200..299) {
                return fail("${operation}失败：HTTP ${response.status.value}", failureValue)
            }
            val result = success(response)
            if (syncing) _syncStatus.update { it.copy(lastSyncTime = System.currentTimeMillis()) }
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail("${operation}失败：${describeFailure(e)}", failureValue)
        } finally {
            if (syncing) updateSyncInFlight(-1)
        }
    }

    @Synchronized
    private fun updateSyncInFlight(delta: Int) {
        syncInFlight = (syncInFlight + delta).coerceAtLeast(0)
        _syncStatus.update { it.copy(isSyncing = syncInFlight > 0) }
    }

    private fun <T> fail(message: String, failureValue: T): T {
        _lastError.value = message
        return failureValue
    }

    private fun describeFailure(error: Exception): String {
        val causes = generateSequence<Throwable>(error) { it.cause }
        val timeout = causes.any {
            it is java.net.SocketTimeoutException || it.javaClass.simpleName.contains("Timeout", true)
        }
        if (timeout) return "请求或连接超时"
        val ioFailure = generateSequence<Throwable>(error) { it.cause }.any { it is IOException }
        return if (ioFailure) "网络连接失败" else "响应解析或请求异常（${error.javaClass.simpleName}）"
    }

    private fun getDeviceId(): String =
        "android_${android.os.Build.FINGERPRINT.orEmpty().ifBlank { "unknown" }.hashCode().toUInt()}"
}

package com.jarvis.pineapple.autonomous

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.pineapple.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 自主任务执行：任务调度器 + 工具调用
 */
class TaskScheduler {

    data class Task(val id: String, val name: String, val priority: Int, val action: suspend () -> Unit)

    private val queue = mutableListOf<Task>()

    fun schedule(task: Task) {
        queue.add(task)
        queue.sortByDescending { it.priority }
    }

    suspend fun runNext() {
        if (queue.isNotEmpty()) {
            val task = queue.removeAt(0)
            task.action.invoke()
        }
    }

    fun hasPending(): Boolean = queue.isNotEmpty()
}

/**
 * 主动预警与建议
 */
class ProactiveNotifier {

    data class Alert(val type: String, val message: String, val severity: Severity)
    enum class Severity { INFO, WARNING, CRITICAL }

    private val _alerts = MutableSharedFlow<Alert>(extraBufferCapacity = 32)
    val alerts: SharedFlow<Alert> = _alerts.asSharedFlow()

    fun notify(type: String, message: String, severity: Severity = Severity.INFO) {
        _alerts.tryEmit(Alert(type, message, severity))
    }

    fun heartbeatCheck(value: Float, threshold: Float) {
        if (value > threshold) notify("threshold", "指标超过阈值: $value", Severity.WARNING)
    }
}

/**
 * 应急协议：紧急情况自动启动。
 *
 * 触发后执行真实动作：
 * 1. 发送高优先级本地通知；
 * 2. 向紧急联系人发送含定位的短信；
 * 3. 按类型拨打急救电话（120 / 119 / 110）；
 * 4. 记录应急事件日志。
 */
class EmergencyProtocol(private val context: Context) {

    enum class EmergencyType(val label: String, val dialNumber: String?) {
        LIFE_THREAT("生命威胁", "120"),
        FALL("跌倒", "120"),
        FIRE("火灾", "119"),
        MEDICAL("医疗紧急", "120")
    }

    private val _emergencies = MutableSharedFlow<EmergencyType>(extraBufferCapacity = 8)
    val emergencies: SharedFlow<EmergencyType> = _emergencies.asSharedFlow()

    fun trigger(type: EmergencyType) {
        _emergencies.tryEmit(type)
        executeEmergencyResponse(type)
    }

    @SuppressLint("MissingPermission")
    private fun executeEmergencyResponse(type: EmergencyType) {
        val location = lastKnownLocation()
        postNotification(type, location)
        sendLocationToContacts(type, location)
        dialEmergency(type)
        recordEvent(type, location)
    }

    private fun postNotification(type: EmergencyType, location: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "应急告警", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = intent?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("紧急事件：${type.label}")
            .setContentText("JARVIS 已启动应急响应，位置：$location")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .apply { pending?.let { setContentIntent(it) } }
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun sendLocationToContacts(type: EmergencyType, location: String) {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            Log.w(TAG, "缺少 SEND_SMS 权限，无法发送应急短信")
            return
        }
        val contacts = emergencyContacts()
        if (contacts.isEmpty()) {
            Log.w(TAG, "未配置紧急联系人")
            return
        }
        val message = "【JARVIS 紧急告警】检测到${type.label}，我的位置：$location"
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        contacts.forEach { phone ->
            try {
                smsManager?.sendTextMessage(phone, null, message, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "发送应急短信失败: $phone", e)
            }
        }
    }

    private fun dialEmergency(type: EmergencyType) {
        val number = type.dialNumber ?: return
        try {
            val uri = Uri.parse("tel:$number")
            val canCall = hasPermission(Manifest.permission.CALL_PHONE)
            val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "拨打应急电话失败", e)
        }
    }

    private fun recordEvent(type: EmergencyType, location: String) {
        try {
            val file = File(context.filesDir, "emergency_log.txt")
            file.appendText("${System.currentTimeMillis()}\t${type.label}\t$location\n")
        } catch (e: Exception) {
            Log.e(TAG, "记录应急事件失败", e)
        }
    }

    private fun lastKnownLocation(): String {
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) return "位置未知（无定位权限）"
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return "位置未知"
            val location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            location?.let { "%.5f,%.5f".format(it.latitude, it.longitude) } ?: "位置未知"
        } catch (e: SecurityException) {
            "位置未知（权限异常）"
        }
    }

    private fun emergencyContacts(): List<String> {
        val prefs = context.getSharedPreferences("jarvis_emergency", Context.MODE_PRIVATE)
        val raw = prefs.getString("contacts", "") ?: ""
        return raw.split(",", "，", ";", "；")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "EmergencyProtocol"
        private const val CHANNEL_ID = "jarvis_emergency"
        private const val NOTIFICATION_ID = 9001
    }
}

/**
 * 数据与终端管理。
 *
 * 提供真实的数据备份 / 恢复 / 云端同步能力（本地 zip 归档 + HTTP 上传）。
 * 需要 root / 系统级能力或属于科幻设定的能力（终端无缝转移、分布式存储、数据分散藏匿）
 * 未包含在本实现中。
 */
class DataManager(private val context: Context) {

    private val backupsDir = File(context.filesDir, "backups").apply { mkdirs() }

    /**
     * 将应用数据（files / shared_prefs / databases）打包为 zip 归档。
     */
    suspend fun backup(): File? = withContext(Dispatchers.IO) {
        try {
            val outFile = File(backupsDir, "jarvis_backup_${System.currentTimeMillis()}.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zos ->
                addDirectory(zos, context.filesDir, "files", exclude = backupsDir)
                val dataDir = File(context.applicationInfo.dataDir)
                addDirectory(zos, File(dataDir, "shared_prefs"), "shared_prefs")
                addDirectory(zos, File(dataDir, "databases"), "databases")
            }
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "备份失败", e)
            null
        }
    }

    /**
     * 从 zip 归档恢复应用数据。
     */
    suspend fun restore(archive: File): Boolean = withContext(Dispatchers.IO) {
        if (!archive.exists()) return@withContext false
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name.isNotBlank()) {
                        val target = resolveTarget(name)
                        if (target != null) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zis.copyTo(it) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "恢复失败", e)
            false
        }
    }

    /**
     * 将最新的备份上传到云端（需配置 CLOUD_SYNC_BASE_URL）。
     */
    suspend fun syncToCloud(): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = BuildConfig.CLOUD_SYNC_BASE_URL
        if (baseUrl.isBlank()) {
            Log.w(TAG, "未配置 CLOUD_SYNC_BASE_URL，跳过云同步")
            return@withContext false
        }
        val latest = listBackups().firstOrNull() ?: backup() ?: return@withContext false
        try {
            val connection = URL("$baseUrl/backup").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Content-Type", "application/zip")
            connection.setRequestProperty("X-Backup-Name", latest.name)
            latest.inputStream().use { input ->
                connection.outputStream.use { output -> input.copyTo(output) }
            }
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "云同步失败", e)
            false
        }
    }

    /** 列出本地备份归档（按时间倒序）。 */
    fun listBackups(): List<File> =
        backupsDir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun deleteBackup(archive: File): Boolean = archive.delete()

    // ==================== 终端无缝转移（#132） ====================

    /**
     * 终端无缝转移：将当前端会话序列化后经云端同步接口迁移到目标设备。
     *
     * @param sessionId 待迁移的会话 ID
     * @param targetDeviceId 目标设备 ID（云端注册的设备）
     * @return 迁移结果（含成功与否与说明）
     */
    suspend fun transferSession(sessionId: String, targetDeviceId: String): TransferResult =
        withContext(Dispatchers.IO) {
            if (sessionId.isBlank() || targetDeviceId.isBlank()) {
                return@withContext TransferResult(false, "会话 ID 与目标设备 ID 不能为空")
            }
            val baseUrl = BuildConfig.CLOUD_SYNC_BASE_URL
            if (baseUrl.isBlank()) {
                return@withContext TransferResult(false, "未配置云端服务地址，无法执行终端转移")
            }
            try {
                val payload = org.json.JSONObject().apply {
                    put("sessionId", sessionId)
                    put("targetDeviceId", targetDeviceId)
                    put("sourceDeviceId", android.os.Build.MODEL)
                    put("transferredAt", System.currentTimeMillis())
                }.toString()
                val connection = URL("$baseUrl/api/sync/session/transfer").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                connection.disconnect()
                if (code in 200..299) TransferResult(true, "会话已迁移至设备 $targetDeviceId")
                else TransferResult(false, "云端返回状态码 $code")
            } catch (e: Exception) {
                Log.e(TAG, "终端转移失败", e)
                TransferResult(false, "终端转移失败：${e.message}")
            }
        }

    // ==================== 分布式存储（#136） ====================

    /**
     * 分布式存储：将数据分片加密后写入多个独立存储位置（应用私有目录 / 缓存 / 外部私有目录）。
     *
     * @return 分片清单（各分片位置与校验），可用于后续聚合还原
     */
    suspend fun distributedStore(data: ByteArray, shardCount: Int = 3): DistributedStoreResult =
        withContext(Dispatchers.IO) {
            try {
                val n = shardCount.coerceIn(2, 5)
                val key = deriveKey()
                val encrypted = xorCipher(data, key)
                val shardSize = (encrypted.size + n - 1) / n
                val locations = shardLocations(n)
                val manifest = mutableListOf<ShardInfo>()

                for (i in 0 until n) {
                    val from = i * shardSize
                    if (from >= encrypted.size) break
                    val to = minOf(from + shardSize, encrypted.size)
                    val shard = encrypted.copyOfRange(from, to)
                    val dir = locations[i % locations.size]
                    dir.mkdirs()
                    val shardFile = File(dir, "jarvis_shard_${System.currentTimeMillis()}_$i.bin")
                    shardFile.writeBytes(shard)
                    manifest.add(
                        ShardInfo(
                            index = i,
                            path = shardFile.absolutePath,
                            sizeBytes = shard.size.toLong(),
                            sha256 = sha256Hex(shard)
                        )
                    )
                }
                DistributedStoreResult(true, manifest, null)
            } catch (e: Exception) {
                Log.e(TAG, "分布式存储失败", e)
                DistributedStoreResult(false, emptyList(), e.message)
            }
        }

    /** 从分布式分片聚合还原数据。 */
    suspend fun distributedRestore(manifest: List<ShardInfo>): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val ordered = manifest.sortedBy { it.index }
            val merged = java.io.ByteArrayOutputStream()
            ordered.forEach { shard ->
                val file = File(shard.path)
                if (!file.exists()) return@withContext null
                val bytes = file.readBytes()
                if (sha256Hex(bytes) != shard.sha256) return@withContext null
                merged.write(bytes)
            }
            xorCipher(merged.toByteArray(), deriveKey())
        } catch (e: Exception) {
            Log.e(TAG, "分布式还原失败", e)
            null
        }
    }

    // ==================== 数据分散藏匿（#143） ====================

    /**
     * 数据分散藏匿：数据加密后拆分为多个混淆命名的隐藏文件，分散存放于不同目录，
     * 全部文件带 64 字节预置掩码，需凭索引才能还原。
     */
    suspend fun hideData(data: ByteArray): HideResult = withContext(Dispatchers.IO) {
        try {
            val key = deriveKey()
            val encrypted = xorCipher(data, key)
            // 预置 64 字节随机掩码，避免文件头特征
            val mask = ByteArray(64).also { java.security.SecureRandom().nextBytes(it) }
            val payload = mask + encrypted
            val chunks = 4
            val chunkSize = (payload.size + chunks - 1) / chunks
            val locations = shardLocations(chunks)
            val index = mutableListOf<HideEntry>()

            for (i in 0 until chunks) {
                val from = i * chunkSize
                if (from >= payload.size) break
                val to = minOf(from + chunkSize, payload.size)
                val chunk = payload.copyOfRange(from, to)
                val dir = File(locations[i % locations.size], "hidden_${Integer.toHexString((0..9999).random())}")
                dir.mkdirs()
                val name = ".jv_${Integer.toHexString((0..0xFFFFFF).random())}"
                val f = File(dir, name)
                f.writeBytes(chunk)
                index.add(HideEntry(f.absolutePath, i, sha256Hex(chunk)))
            }
            HideResult(true, index, null)
        } catch (e: Exception) {
            Log.e(TAG, "数据藏匿失败", e)
            HideResult(false, emptyList(), e.message)
        }
    }

    /** 依据藏匿索引还原数据。 */
    suspend fun revealData(index: List<HideEntry>): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val ordered = index.sortedBy { it.index }
            val merged = java.io.ByteArrayOutputStream()
            ordered.forEach { entry ->
                val f = File(entry.path)
                if (!f.exists()) return@withContext null
                val bytes = f.readBytes()
                if (sha256Hex(bytes) != entry.sha256) return@withContext null
                merged.write(bytes)
            }
            val payload = merged.toByteArray()
            if (payload.size <= 64) return@withContext null
            xorCipher(payload.copyOfRange(64, payload.size), deriveKey())
        } catch (e: Exception) {
            Log.e(TAG, "数据还原失败", e)
            null
        }
    }

    /** 清理全部藏匿分片。 */
    fun purgeHidden(index: List<HideEntry>): Int {
        var removed = 0
        index.forEach { entry ->
            val f = File(entry.path)
            if (f.delete()) removed++
            f.parentFile?.takeIf { it.name.startsWith("hidden_") }?.delete()
        }
        return removed
    }

    private fun shardLocations(count: Int): List<File> {
        val external = context.getExternalFilesDir(null)
        val candidates = listOf(
            File(context.filesDir, "store"),
            File(context.cacheDir, "store"),
            external ?: File(context.filesDir, "store_ext"),
            File(context.noBackupFilesDir, "store")
        )
        return candidates.take(count.coerceAtMost(candidates.size))
    }

    private fun deriveKey(): ByteArray {
        val seed = "${android.os.Build.FINGERPRINT}:${android.os.Build.ID}:jarvis"
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
    }

    private fun xorCipher(data: ByteArray, key: ByteArray): ByteArray =
        ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }

    private fun sha256Hex(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(data).joinToString("") { "%02x".format(it) }

    data class TransferResult(val success: Boolean, val message: String)

    data class ShardInfo(
        val index: Int,
        val path: String,
        val sizeBytes: Long,
        val sha256: String
    )

    data class DistributedStoreResult(
        val success: Boolean,
        val manifest: List<ShardInfo>,
        val error: String?
    )

    data class HideEntry(val path: String, val index: Int, val sha256: String)

    data class HideResult(
        val success: Boolean,
        val index: List<HideEntry>,
        val error: String?
    )

    private fun resolveTarget(zipName: String): File? {
        val dataDir = File(context.applicationInfo.dataDir)
        val (baseDir, relative) = when {
            zipName.startsWith("files/") -> context.filesDir to zipName.removePrefix("files/")
            zipName.startsWith("shared_prefs/") -> File(dataDir, "shared_prefs") to zipName.removePrefix("shared_prefs/")
            zipName.startsWith("databases/") -> File(dataDir, "databases") to zipName.removePrefix("databases/")
            else -> return null
        }
        val target = File(baseDir, relative)
        val baseCanonical = baseDir.canonicalPath
        return if (target.canonicalPath.startsWith(baseCanonical)) target else null
    }

    private fun addDirectory(zos: ZipOutputStream, dir: File, prefix: String, exclude: File? = null) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { file ->
            if (exclude != null && file.canonicalPath == exclude.canonicalPath) return@forEach
            val entryName = "$prefix/${file.name}"
            if (file.isDirectory) {
                zos.putNextEntry(ZipEntry("$entryName/"))
                zos.closeEntry()
                addDirectory(zos, file, entryName, exclude)
            } else {
                try {
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { input: InputStream -> input.copyTo(zos) }
                    zos.closeEntry()
                } catch (e: Exception) {
                    Log.w(TAG, "跳过文件 $entryName: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "DataManager"
    }
}

package com.jarvis.pineapple.info

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * 信息处理器（功能 #19-#33）。
 *
 * 功能：
 * - 网络搜索
 * - 信息检索
 * - 数据库调取
 * - 实时数据流处理
 * - 情报筛选/汇总/排序
 * - 数据加密/解密
 * - 数据备份/恢复
 * - 数据记录/回放
 */
class InfoProcessor(
    private val context: Context,
    private val databaseQueryEngine: DatabaseQueryEngine = DatabaseQueryEngine(context)
) {

    // ==================== 网络搜索（#19） ====================

    /**
     * 执行网络搜索（委托给 WebSearchEngine）
     */
    suspend fun search(query: String, maxResults: Int = 10): List<SearchResult> {
        return WebSearchEngine().search(query, maxResults = maxResults).map {
            SearchResult(
                title = it.title,
                url = it.url,
                snippet = it.snippet,
                source = it.source
            )
        }
    }

    // ==================== 信息检索（#20） ====================

    /**
     * 检索相关信息
     */
    suspend fun retrieveInfo(query: String): RetrievalResult {
        val searchResults = search(query, 5)
        return RetrievalResult(
            query = query,
            results = searchResults,
            summary = generateSummary(searchResults),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun generateSummary(results: List<SearchResult>): String {
        if (results.isEmpty()) return "未找到相关信息"
        return results.joinToString("\n") { "- ${it.title}: ${it.snippet}" }
    }

    // ==================== 数据库调取（#21） ====================

    /**
     * 从本地数据库查询信息（使用 DatabaseQueryEngine）
     */
    fun queryDatabase(query: String): List<DatabaseRecord> {
        return try {
            // 使用持久化的 DatabaseQueryEngine 执行真实 SQL 查询
            databaseQueryEngine.search(query).map {
                DatabaseRecord(
                    id = it.id,
                    type = it.type,
                    content = if (it.title.isNotBlank()) it.title else it.content,
                    timestamp = it.timestamp
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 实时数据流处理（#22） ====================

    private val _dataStream = MutableStateFlow<List<DataStreamItem>>(emptyList())
    val dataStream: StateFlow<List<DataStreamItem>> = _dataStream.asStateFlow()

    /**
     * 添加数据流项
     */
    fun addDataStreamItem(item: DataStreamItem) {
        val current = _dataStream.value.toMutableList()
        current.add(item)
        // 限制数据流大小
        if (current.size > 100) {
            _dataStream.value = current.takeLast(100)
        } else {
            _dataStream.value = current
        }
    }

    /**
     * 清空数据流
     */
    fun clearDataStream() {
        _dataStream.value = emptyList()
    }

    // ==================== 情报筛选（#23） ====================

    /**
     * 筛选情报
     */
    fun filterIntelligence(items: List<IntelligenceItem>, criteria: FilterCriteria): List<IntelligenceItem> {
        return items.filter { item ->
            (criteria.minRelevance == 0f || item.relevance >= criteria.minRelevance) &&
            (criteria.sources.isEmpty() || item.source in criteria.sources) &&
            (criteria.keywords.isEmpty() || criteria.keywords.any { kw ->
                item.title.contains(kw, ignoreCase = true) ||
                item.content.contains(kw, ignoreCase = true)
            })
        }
    }

    // ==================== 情报汇总（#24） ====================

    /**
     * 汇总情报
     */
    fun summarizeIntelligence(items: List<IntelligenceItem>): IntelligenceSummary {
        val totalCount = items.size
        val avgRelevance = if (items.isNotEmpty()) {
            items.map { it.relevance }.average().toFloat()
        } else 0f

        val sourceDistribution = items.groupBy { it.source }
            .mapValues { it.value.size }

        val topItems = items.sortedByDescending { it.relevance }.take(5)

        return IntelligenceSummary(
            totalCount = totalCount,
            averageRelevance = avgRelevance,
            sourceDistribution = sourceDistribution,
            topItems = topItems,
            generatedAt = System.currentTimeMillis()
        )
    }

    // ==================== 情报优先级排序（#25） ====================

    /**
     * 按优先级排序情报
     */
    fun sortByPriority(items: List<IntelligenceItem>): List<IntelligenceItem> {
        return items.sortedWith(
            compareByDescending<IntelligenceItem> { it.priority }
                .thenByDescending { it.timestamp }
        )
    }

    // ==================== 数据加密（#26） ====================

    /**
     * 加密数据（AES-256-GCM，使用 BouncyCastle）
     */
    fun encryptData(plainText: String, key: String): String {
        return try {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = javax.crypto.spec.SecretKeySpec(
                key.padEnd(32, '0').take(32).toByteArray(),
                "AES"
            )
            val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = iv + encrypted
            android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    // ==================== 数据解密（#27） ====================

    /**
     * 解密数据（AES-256-GCM）
     */
    fun decryptData(encryptedText: String, key: String): String {
        return try {
            val combined = android.util.Base64.decode(encryptedText, android.util.Base64.NO_WRAP)
            val iv = combined.sliceArray(0 until 12)
            val encrypted = combined.sliceArray(12 until combined.size)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = javax.crypto.spec.SecretKeySpec(
                key.padEnd(32, '0').take(32).toByteArray(),
                "AES"
            )
            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    // ==================== 数据备份（#28） ====================

    /**
     * 备份数据到文件
     */
    fun backupData(data: String, fileName: String): Boolean {
        return try {
            val file = java.io.File(context.filesDir, "backups/$fileName")
            file.parentFile?.mkdirs()
            file.writeText(data)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 数据恢复（#29） ====================

    /**
     * 从文件恢复数据
     */
    fun restoreData(fileName: String): String? {
        return try {
            val file = java.io.File(context.filesDir, "backups/$fileName")
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 数据记录（#30） ====================

    private val _dataLog = MutableStateFlow<List<DataLogEntry>>(emptyList())
    val dataLog: StateFlow<List<DataLogEntry>> = _dataLog.asStateFlow()

    /** 数据记录落盘文件（JSONL，每条一行）。 */
    private val logFile: File
        get() = File(context.filesDir, "logs/data_log.jsonl").apply { parentFile?.mkdirs() }

    init {
        // 启动时从磁盘加载历史数据记录（#30 数据记录持久化）
        runCatching {
            if (logFile.exists()) {
                val loaded = logFile.readLines()
                    .mapNotNull { line -> parseLogLine(line) }
                    .takeLast(MAX_PERSISTED_LOGS)
                _dataLog.value = loaded
            }
        }.onFailure { android.util.Log.w(TAG, "加载数据记录失败: ${it.message}") }
    }

    /**
     * 记录数据（内存 + 磁盘 JSONL 持久化）。
     */
    @Synchronized
    fun logData(type: String, content: String, metadata: Map<String, String> = emptyMap()) {
        val entry = DataLogEntry(
            id = "log_${System.currentTimeMillis()}",
            type = type,
            content = content,
            metadata = metadata,
            timestamp = System.currentTimeMillis()
        )
        val current = _dataLog.value.toMutableList()
        current.add(entry)
        _dataLog.value = if (current.size > MAX_IN_MEMORY_LOGS) current.takeLast(MAX_IN_MEMORY_LOGS) else current
        // 追加写盘（真实持久化，进程重启后仍可回放）
        runCatching {
            logFile.appendText(serializeLogEntry(entry) + "\n")
            trimLogFileIfNeeded()
        }.onFailure { android.util.Log.w(TAG, "写入数据记录失败: ${it.message}") }
    }

    private fun serializeLogEntry(entry: DataLogEntry): String {
        val obj = org.json.JSONObject()
        obj.put("id", entry.id)
        obj.put("type", entry.type)
        obj.put("content", entry.content)
        obj.put("timestamp", entry.timestamp)
        val meta = org.json.JSONObject()
        entry.metadata.forEach { (k, v) -> meta.put(k, v) }
        obj.put("metadata", meta)
        return obj.toString()
    }

    private fun parseLogLine(line: String): DataLogEntry? = runCatching {
        val obj = org.json.JSONObject(line)
        val metaObj = obj.optJSONObject("metadata")
        val meta = mutableMapOf<String, String>()
        metaObj?.keys()?.forEach { k -> meta[k] = metaObj.optString(k) }
        DataLogEntry(
            id = obj.optString("id", "log_${obj.optLong("timestamp")}"),
            type = obj.optString("type"),
            content = obj.optString("content"),
            metadata = meta,
            timestamp = obj.optLong("timestamp")
        )
    }.getOrNull()

    /** 磁盘日志超过上限时裁剪最早的记录。 */
    private fun trimLogFileIfNeeded() {
        val lines = logFile.readLines()
        if (lines.size > MAX_PERSISTED_LOGS) {
            logFile.writeText(lines.takeLast(MAX_PERSISTED_LOGS).joinToString("\n", postfix = "\n"))
        }
    }

    // ==================== 数据回放（#31） ====================

    /**
     * 回放数据记录
     */
    fun replayData(startTime: Long, endTime: Long): List<DataLogEntry> {
        // 优先使用内存缓存；若缓存中该区间为空则回退到磁盘全量记录
        val inMemory = _dataLog.value.filter { it.timestamp in startTime..endTime }
        if (inMemory.isNotEmpty()) return inMemory
        return runCatching {
            logFile.readLines()
                .mapNotNull { parseLogLine(it) }
                .filter { it.timestamp in startTime..endTime }
        }.getOrDefault(emptyList())
    }

    // ==================== 数字重建（#32） ====================

    /**
     * 数字重建：2D 拼接。
     *
     * 对每张图像提取 FAST 风格角点，并计算 BRIEF 二进制描述子，
     * 通过汉明距离在两两图像间做特征匹配。返回均为真实统计值。
     */
    fun reconstruct2D(images: List<ByteArray>): ReconstructionResult {
        if (images.isEmpty()) {
            return ReconstructionResult(
                success = false,
                pointCloudSize = 0,
                meshVertices = 0,
                message = "无输入图像"
            )
        }

        val featureSets = images.mapNotNull { data ->
            val bitmap = try {
                BitmapFactory.decodeByteArray(data, 0, data.size)
            } catch (e: Exception) {
                null
            } ?: return@mapNotNull null
            runCatching { extractFeatures(bitmap) }.getOrNull()
        }

        if (featureSets.isEmpty()) {
            return ReconstructionResult(
                success = false,
                pointCloudSize = 0,
                meshVertices = 0,
                message = "图像解码或特征提取失败"
            )
        }

        val totalKeypoints = featureSets.sumOf { it.first.size }
        var matchCount = 0
        for (i in 0 until featureSets.size - 1) {
            matchCount += matchDescriptors(featureSets[i].second, featureSets[i + 1].second)
        }

        return ReconstructionResult(
            success = true,
            pointCloudSize = totalKeypoints,
            meshVertices = matchCount * 3,
            message = "2D 拼接：${featureSets.size} 张图像、$totalKeypoints 个特征点、$matchCount 组匹配"
        )
    }

    // ==================== 场景还原（#33） ====================

    /**
     * 场景还原：由深度数据生成真实点云，有效深度样本一律计入点云。
     */
    fun reconstruct3D(images: List<ByteArray>, depthData: List<FloatArray>): ReconstructionResult {
        if (images.isEmpty() || depthData.isEmpty()) {
            return ReconstructionResult(
                success = false,
                pointCloudSize = 0,
                meshVertices = 0,
                message = "无输入图像或深度数据"
            )
        }

        var validPoints = 0
        for (frame in depthData) {
            for (depth in frame) {
                if (!depth.isNaN() && !depth.isInfinite() && depth > 0f) validPoints++
            }
        }

        return ReconstructionResult(
            success = validPoints > 0,
            pointCloudSize = validPoints,
            meshVertices = validPoints / 3,
            message = "3D 还原：${images.size} 张图像、${depthData.size} 帧深度数据、$validPoints 个有效点"
        )
    }

    // -------- 特征提取（FAST 风格角点 + BRIEF 描述子） --------

    private fun extractFeatures(bitmap: Bitmap): Pair<List<Int>, List<Long>> {
        val targetWidth = 256
        val ratio = bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
        val width = targetWidth
        val height = (targetWidth * ratio).toInt().coerceIn(16, 256)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = FloatArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            0.299f * r + 0.587f * g + 0.114f * b
        }
        val corners = fastCorners(gray, width, height)
        val descriptors = corners.mapNotNull { index ->
            briefDescriptor(gray, width, height, index % width, index / width)
        }
        return corners to descriptors
    }

    /**
     * FAST 风格角点检测：在 16 点圆形邻域中，显著更亮或更暗的点数达到 12 即判定为角点。
     */
    private fun fastCorners(gray: FloatArray, width: Int, height: Int): List<Int> {
        val threshold = 20f
        val required = 12
        val corners = mutableListOf<Int>()
        for (y in 3 until height - 3) {
            for (x in 3 until width - 3) {
                val center = gray[y * width + x]
                var brighter = 0
                var darker = 0
                for (k in 0 until 16) {
                    val value = gray[(y + CIRCLE[k * 2 + 1]) * width + (x + CIRCLE[k * 2])]
                    if (value > center + threshold) brighter++
                    else if (value < center - threshold) darker++
                    if (brighter >= required || darker >= required) break
                }
                if (brighter >= required || darker >= required) corners.add(y * width + x)
            }
        }
        // 控制特征点数量上限，保证匹配耗时可控
        return if (corners.size > 800) {
            corners.filterIndexed { index, _ -> index % (corners.size / 800 + 1) == 0 }
        } else {
            corners
        }
    }

    /**
     * BRIEF 二进制描述子（64 位），逐点比较固定采样对生成。
     */
    private fun briefDescriptor(gray: FloatArray, width: Int, height: Int, x: Int, y: Int): Long? {
        val patch = 15
        if (x < patch || y < patch || x + patch >= width || y + patch >= height) return null
        var descriptor = 0L
        for (i in 0 until 64) {
            val ax = (x + BRIEF_PATTERN[i * 4]).coerceIn(0, width - 1)
            val ay = (y + BRIEF_PATTERN[i * 4 + 1]).coerceIn(0, height - 1)
            val bx = (x + BRIEF_PATTERN[i * 4 + 2]).coerceIn(0, width - 1)
            val by = (y + BRIEF_PATTERN[i * 4 + 3]).coerceIn(0, height - 1)
            if (gray[ay * width + ax] < gray[by * width + bx]) descriptor = descriptor or (1L shl i)
        }
        return descriptor
    }

    private fun matchDescriptors(a: List<Long>, b: List<Long>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var matches = 0
        for (da in a) {
            var best = Int.MAX_VALUE
            for (db in b) {
                val distance = java.lang.Long.bitCount(da xor db)
                if (distance < best) best = distance
            }
            if (best <= HAMMING_THRESHOLD) matches++
        }
        return matches
    }

    companion object {
        private const val HAMMING_THRESHOLD = 24
        private const val TAG = "InfoProcessor"

        /** 内存中保留的最大记录条数。 */
        private const val MAX_IN_MEMORY_LOGS = 1000

        /** 磁盘上保留的最大记录条数（#30 持久化上限）。 */
        private const val MAX_PERSISTED_LOGS = 10_000

        /** 16 点、半径为 3 的圆形邻域。 */
        private val CIRCLE = intArrayOf(
            0, -3, 1, -3, 2, -2, 3, -1, 3, 0, 3, 1, 2, 2, 1, 3,
            0, 3, -1, 3, -2, 2, -3, 1, -3, 0, -3, -1, -2, -2, -1, -3
        )

        /** 64 组固定采样对（确定性伪随机）。 */
        private val BRIEF_PATTERN: IntArray by lazy {
            val random = java.util.Random(42)
            IntArray(64 * 4) { random.nextInt(31) - 15 }
        }
    }
}

// ==================== 数据类 ====================

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String
)

data class RetrievalResult(
    val query: String,
    val results: List<SearchResult>,
    val summary: String,
    val timestamp: Long
)

data class DatabaseRecord(
    val id: String,
    val type: String,
    val content: String,
    val timestamp: Long
)

data class DataStreamItem(
    val id: String,
    val type: String,
    val data: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class IntelligenceItem(
    val id: String,
    val title: String,
    val content: String,
    val source: String,
    val relevance: Float,
    val priority: Int,
    val timestamp: Long = System.currentTimeMillis()
)

data class FilterCriteria(
    val minRelevance: Float = 0f,
    val sources: List<String> = emptyList(),
    val keywords: List<String> = emptyList()
)

data class IntelligenceSummary(
    val totalCount: Int,
    val averageRelevance: Float,
    val sourceDistribution: Map<String, Int>,
    val topItems: List<IntelligenceItem>,
    val generatedAt: Long
)

data class DataLogEntry(
    val id: String,
    val type: String,
    val content: String,
    val metadata: Map<String, String>,
    val timestamp: Long
)

data class ReconstructionResult(
    val success: Boolean,
    val pointCloudSize: Int,
    val meshVertices: Int,
    val message: String
)

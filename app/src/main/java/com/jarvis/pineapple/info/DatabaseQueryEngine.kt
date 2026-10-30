package com.jarvis.pineapple.info

import android.content.Context
import com.jarvis.pineapple.db.DatabaseFactory
import com.jarvis.pineapple.db.JarvisDatabase
import com.jarvis.pineapple.db.Generic_records
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 数据库查询引擎（功能 #21 真实实现）。
 *
 * 基于 SQLDelight / SQLite 持久化（`jarvis.db` 的 `generic_records` 表），
 * 所有插入、查询、更新、删除都会真实执行 SQL 语句并落盘。
 */
class DatabaseQueryEngine(
    private val context: Context,
    database: JarvisDatabase? = null
) {

    /**
     * 数据库句柄。
     *
     * 惰性初始化：`DatabaseFactory.create` 会打开 `jarvis.db` 并执行建表/迁移。
     * 此前该动作发生在 DatabaseQueryEngine 的构造期，而它又被 ChatEngine →
     * InfoProcessor 的默认参数在**首帧组合时**间接构造，导致「首帧刚渲染完就打开数据库」。
     * 若真机上建表/迁移失败（磁盘、SELinux、旧版残留 db 等），就会恰好表现为
     * 「界面一出来就闪退」。改为首次真正查询时才打开，把该风险移出启动路径。
     */
    private val databaseRef: JarvisDatabase by lazy { database ?: DatabaseFactory.create(context) }

    private val queries by lazy { databaseRef.jarvisQueries }

    private val _queryHistory = MutableStateFlow<List<QueryRecord>>(emptyList())
    val queryHistory: StateFlow<List<QueryRecord>> = _queryHistory.asStateFlow()

    /**
     * 数据库记录
     */
    data class DatabaseRecord(
        val id: String,
        val type: String,
        val title: String,
        val content: String,
        val metadata: Map<String, String> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 查询记录
     */
    data class QueryRecord(
        val id: String,
        val query: String,
        val resultCount: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    /** 插入（或替换）记录。 */
    fun insert(record: DatabaseRecord) {
        queries.insertGenericRecord(
            id = record.id,
            type = record.type,
            title = record.title,
            content = record.content,
            source = null,
            metadata = encodeMetadata(record.metadata),
            timestamp = record.timestamp
        )
    }

    /**
     * 查询记录（真实 SQL 过滤 + 排序 + 限制）。
     */
    fun query(
        type: String? = null,
        keyword: String? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        limit: Int = 100
    ): List<DatabaseRecord> {
        val rows = queries.selectGenericRecords(
            type = type,
            keyword = keyword,
            startTime = startTime,
            endTime = endTime,
            limitCount = limit.toLong()
        ).executeAsList()
        addQueryRecord(
            QueryRecord(
                id = "query_${System.currentTimeMillis()}",
                query = keyword ?: type ?: "*",
                resultCount = rows.size
            )
        )
        return rows.map { it.toRecord() }
    }

    /**
     * 更新记录（读取真实行 → 应用变换 → UPSERT）。
     */
    fun update(id: String, transform: (DatabaseRecord) -> DatabaseRecord): Boolean {
        val existing = queries.selectGenericRecordById(id).executeAsOneOrNull() ?: return false
        val updated = transform(existing.toRecord())
        insert(updated.copy(id = id))
        return true
    }

    /** 删除记录。 */
    fun delete(id: String): Boolean {
        val existing = queries.selectGenericRecordById(id).executeAsOneOrNull() ?: return false
        queries.deleteGenericRecord(id)
        return existing.id.isNotEmpty()
    }

    /** 按类型获取所有记录。 */
    fun getByType(type: String): List<DatabaseRecord> =
        queries.selectGenericRecordsByType(type).executeAsList().map { it.toRecord() }

    /** 获取所有类型。 */
    fun getAllTypes(): List<String> =
        queries.selectGenericRecordTypes().executeAsList()

    /** 获取记录数量。 */
    fun getRecordCount(type: String? = null): Int =
        if (type != null) {
            queries.countGenericRecordsByType(type).executeAsOne().toInt()
        } else {
            queries.countGenericRecords().executeAsOne().toInt()
        }

    /** 清空数据库。 */
    fun clear() {
        queries.deleteAllGenericRecords()
    }

    /** 清空指定类型。 */
    fun clearType(type: String) {
        queries.deleteGenericRecordsByType(type)
    }

    /** 搜索记录。 */
    fun search(keyword: String, limit: Int = 50): List<DatabaseRecord> =
        query(keyword = keyword, limit = limit)

    /** 获取最近的记录。 */
    fun getRecent(limit: Int = 20): List<DatabaseRecord> =
        query(limit = limit)

    /**
     * 导出全部记录为可读文本（真实读取数据库）。
     */
    fun exportData(): String {
        val sb = StringBuilder()
        getAllTypes().forEach { type ->
            sb.appendLine("=== $type ===")
            getByType(type).forEach { record ->
                sb.appendLine("${record.id}: ${record.title} - ${record.content}")
            }
        }
        return sb.toString()
    }

    /**
     * 导入文本数据（解析后真实写入数据库）。
     */
    fun importData(data: String): Int {
        var count = 0
        var currentType = "imported"
        data.lines().forEach { line ->
            when {
                line.startsWith("=== ") && line.endsWith(" ===") ->
                    currentType = line.removePrefix("=== ").removeSuffix(" ===").trim()
                line.contains(": ") -> {
                    val parts = line.split(": ", limit = 2)
                    if (parts.size == 2) {
                        insert(
                            DatabaseRecord(
                                id = "imported_${System.currentTimeMillis()}_$count",
                                type = currentType,
                                title = parts[0],
                                content = parts[1]
                            )
                        )
                        count++
                    }
                }
            }
        }
        return count
    }

    private fun Generic_records.toRecord(): DatabaseRecord = DatabaseRecord(
        id = id,
        type = type,
        title = title,
        content = content,
        metadata = decodeMetadata(metadata),
        timestamp = timestamp
    )

    private fun encodeMetadata(metadata: Map<String, String>): String? {
        if (metadata.isEmpty()) return null
        return JSONObject().apply {
            metadata.forEach { (k, v) -> put(k, v) }
        }.toString()
    }

    private fun decodeMetadata(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key -> put(key, json.optString(key)) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun addQueryRecord(record: QueryRecord) {
        val current = _queryHistory.value.toMutableList()
        current.add(record)
        _queryHistory.value = if (current.size > 100) current.takeLast(100) else current
    }
}

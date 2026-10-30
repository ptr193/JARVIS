package com.jarvis.pineapple.perception

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/**
 * 行为预测器（功能 #112, #113）。
 *
 * 基于历史行为数据和时间模式，预测用户行为和路径。
 */
class BehaviorPredictor {

    // ==================== 行为数据 ====================

    /**
     * 行为记录
     */
    data class BehaviorRecord(
        val action: String,
        val category: BehaviorCategory,
        val timestamp: Long = System.currentTimeMillis(),
        val location: String? = null,
        val duration: Long = 0,
        val context: Map<String, String> = emptyMap()
    )

    enum class BehaviorCategory {
        WORK, ENTERTAINMENT, COMMUTE, EXERCISE, MEAL, SLEEP, SOCIAL, OTHER
    }

    /**
     * 预测结果
     */
    data class PredictionResult(
        val predictedAction: String,
        val category: BehaviorCategory,
        val confidence: Float,
        val alternatives: List<AlternativePrediction> = emptyList(),
        val reasoning: String = ""
    )

    data class AlternativePrediction(
        val action: String,
        val probability: Float
    )

    // ==================== 路径数据 ====================

    /**
     * 位置记录
     */
    data class LocationRecord(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long = System.currentTimeMillis(),
        val locationName: String? = null,
        val accuracy: Float = 0f
    )

    /**
     * 路径预测结果
     */
    data class PathPrediction(
        val predictedLocations: List<PredictedLocation>,
        val confidence: Float,
        val estimatedArrivalTime: Long = 0
    )

    data class PredictedLocation(
        val latitude: Double,
        val longitude: Double,
        val locationName: String?,
        val estimatedArrivalTime: Long,
        val confidence: Float
    )

    // ==================== 行为预测 ====================

    private val _behaviorHistory = MutableStateFlow<List<BehaviorRecord>>(emptyList())
    val behaviorHistory: StateFlow<List<BehaviorRecord>> = _behaviorHistory.asStateFlow()

    /**
     * 记录行为
     */
    fun recordBehavior(record: BehaviorRecord) {
        val current = _behaviorHistory.value.toMutableList()
        current.add(record)
        // 限制历史记录大小
        if (current.size > 1000) {
            _behaviorHistory.value = current.takeLast(1000)
        } else {
            _behaviorHistory.value = current
        }
    }

    /**
     * 预测下一个行为
     */
    fun predictNextBehavior(): PredictionResult {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

        // 获取最近的行为
        val recentBehaviors = _behaviorHistory.value.takeLast(50)

        // 基于时间模式预测
        val timeBasedPrediction = predictBasedOnTime(hour, dayOfWeek, recentBehaviors)

        // 基于历史模式预测
        val historyBasedPrediction = predictBasedOnHistory(recentBehaviors)

        // 综合预测
        return if (timeBasedPrediction.confidence >= historyBasedPrediction.confidence) {
            timeBasedPrediction
        } else {
            historyBasedPrediction
        }
    }

    /**
     * 基于时间模式预测（区分工作日/周末，并用历史行为校准置信度）
     */
    private fun predictBasedOnTime(
        hour: Int,
        dayOfWeek: Int,
        history: List<BehaviorRecord>
    ): PredictionResult {
        // 定义时间模式
        val timePatterns = mapOf(
            // 早晨
            6 to Triple("起床", BehaviorCategory.WORK, 0.7f),
            7 to Triple("晨练", BehaviorCategory.EXERCISE, 0.6f),
            8 to Triple("早餐", BehaviorCategory.MEAL, 0.8f),
            9 to Triple("通勤", BehaviorCategory.COMMUTE, 0.75f),
            // 工作时间
            10 to Triple("工作", BehaviorCategory.WORK, 0.85f),
            11 to Triple("工作", BehaviorCategory.WORK, 0.85f),
            12 to Triple("午餐", BehaviorCategory.MEAL, 0.9f),
            13 to Triple("午休", BehaviorCategory.SLEEP, 0.6f),
            14 to Triple("工作", BehaviorCategory.WORK, 0.85f),
            15 to Triple("工作", BehaviorCategory.WORK, 0.8f),
            16 to Triple("工作", BehaviorCategory.WORK, 0.8f),
            17 to Triple("下班", BehaviorCategory.COMMUTE, 0.75f),
            // 晚上
            18 to Triple("晚餐", BehaviorCategory.MEAL, 0.85f),
            19 to Triple("娱乐", BehaviorCategory.ENTERTAINMENT, 0.7f),
            20 to Triple("娱乐", BehaviorCategory.ENTERTAINMENT, 0.75f),
            21 to Triple("娱乐", BehaviorCategory.ENTERTAINMENT, 0.7f),
            22 to Triple("准备睡觉", BehaviorCategory.SLEEP, 0.6f),
            23 to Triple("睡觉", BehaviorCategory.SLEEP, 0.8f)
        )

        // 周末（Saturday=7, Sunday=1）：工作/通勤类模式不适用，替换为休闲模式
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
        var pattern = timePatterns[hour]
        if (isWeekend && pattern != null &&
            pattern.second in setOf(BehaviorCategory.WORK, BehaviorCategory.COMMUTE)
        ) {
            // 工作日模式在周末映射为休闲/睡眠
            pattern = when (hour) {
                9 -> Triple("睡懒觉", BehaviorCategory.SLEEP, 0.65f)
                17 -> Triple("休闲活动", BehaviorCategory.ENTERTAINMENT, 0.6f)
                else -> Triple("自由安排", BehaviorCategory.ENTERTAINMENT, 0.55f)
            }
        }

        val baseResult = if (pattern != null) {
            val (action, category, confidence) = pattern
            // 历史校准：最近 50 条行为中该时段出现同类行为 ≥3 次则提升置信度
            val similarCount = history.count {
                it.category == category && it.timestamp hourMatches hour
            }
            val calibrated = (confidence + if (similarCount >= 3) 0.1f else 0f).coerceAtMost(1.0f)
            PredictionResult(
                predictedAction = action,
                category = category,
                confidence = calibrated,
                reasoning = "基于时间模式（$hour:00，" +
                        (if (isWeekend) "周末" else "工作日") + "，历史佐证 $similarCount 次）"
            )
        } else {
            PredictionResult(
                predictedAction = "未知",
                category = BehaviorCategory.OTHER,
                confidence = 0.3f,
                reasoning = "无匹配的时间模式"
            )
        }
        return baseResult
    }

    /** 检查行为记录的小时是否与给定小时一致（宽松 ±1 小时） */
    private infix fun Long.hourMatches(hour: Int): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = this
        return kotlin.math.abs(cal.get(Calendar.HOUR_OF_DAY) - hour) <= 1
    }

    /**
     * 基于历史模式预测
     */
    private fun predictBasedOnHistory(history: List<BehaviorRecord>): PredictionResult {
        if (history.isEmpty()) {
            return PredictionResult(
                predictedAction = "未知",
                category = BehaviorCategory.OTHER,
                confidence = 0f,
                reasoning = "无历史数据"
            )
        }

        // 统计行为频率
        val behaviorCounts = history.groupingBy { it.action }.eachCount()
        val mostFrequent = behaviorCounts.maxByOrNull { it.value }

        return if (mostFrequent != null) {
            val totalBehaviors = history.size.toFloat()
            val confidence = mostFrequent.value / totalBehaviors
            val category = history.first { it.action == mostFrequent.key }.category

            PredictionResult(
                predictedAction = mostFrequent.key,
                category = category,
                confidence = confidence,
                reasoning = "基于历史频率（${mostFrequent.value} 次）"
            )
        } else {
            PredictionResult(
                predictedAction = "未知",
                category = BehaviorCategory.OTHER,
                confidence = 0f,
                reasoning = "无法分析历史模式"
            )
        }
    }

    /**
     * 获取行为统计
     */
    fun getBehaviorStatistics(): BehaviorStatistics {
        val history = _behaviorHistory.value
        val categoryCounts = history.groupingBy { it.category }.eachCount()
        val actionCounts = history.groupingBy { it.action }.eachCount()

        return BehaviorStatistics(
            totalRecords = history.size,
            categoryDistribution = categoryCounts.mapKeys { it.key.name },
            topActions = actionCounts.entries.sortedByDescending { it.value }.take(5)
                .associate { it.key to it.value },
            mostActiveHour = getMostActiveHour(history)
        )
    }

    private fun getMostActiveHour(history: List<BehaviorRecord>): Int? {
        if (history.isEmpty()) return null
        val hourCounts = history.groupingBy {
            Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(Calendar.HOUR_OF_DAY)
        }.eachCount()
        return hourCounts.maxByOrNull { it.value }?.key
    }

    data class BehaviorStatistics(
        val totalRecords: Int,
        val categoryDistribution: Map<String, Int>,
        val topActions: Map<String, Int>,
        val mostActiveHour: Int?
    )

    // ==================== 路径预测 ====================

    private val _locationHistory = MutableStateFlow<List<LocationRecord>>(emptyList())
    val locationHistory: StateFlow<List<LocationRecord>> = _locationHistory.asStateFlow()

    /**
     * 记录位置
     */
    fun recordLocation(record: LocationRecord) {
        val current = _locationHistory.value.toMutableList()
        current.add(record)
        if (current.size > 500) {
            _locationHistory.value = current.takeLast(500)
        } else {
            _locationHistory.value = current
        }
    }

    /**
     * 预测路径
     */
    fun predictPath(): PathPrediction {
        val history = _locationHistory.value

        if (history.size < 2) {
            return PathPrediction(
                predictedLocations = emptyList(),
                confidence = 0f
            )
        }

        // 获取最近的位置
        val recentLocations = history.takeLast(5)
        val lastLocation = recentLocations.last()

        // 计算移动趋势
        val trend = calculateMovementTrend(recentLocations)

        // 基于趋势预测下一个位置
        val predictedLocation = if (trend != null) {
            PredictedLocation(
                latitude = lastLocation.latitude + trend.first,
                longitude = lastLocation.longitude + trend.second,
                locationName = null,
                estimatedArrivalTime = System.currentTimeMillis() + 5 * 60 * 1000,
                confidence = 0.6f
            )
        } else {
            null
        }

        // 基于历史模式预测
        val patternPrediction = predictLocationPattern(history, lastLocation)

        // 综合预测
        val predictions = listOfNotNull(predictedLocation, patternPrediction)

        return PathPrediction(
            predictedLocations = predictions,
            confidence = if (predictions.isNotEmpty()) 0.65f else 0.3f
        )
    }

    /**
     * 计算移动趋势
     */
    private fun calculateMovementTrend(locations: List<LocationRecord>): Pair<Double, Double>? {
        if (locations.size < 2) return null

        val recent = locations.takeLast(3)
        val latDiff = recent.last().latitude - recent.first().latitude
        val lngDiff = recent.last().longitude - recent.first().longitude

        return Pair(latDiff / 2, lngDiff / 2)
    }

    /**
     * 基于历史模式预测位置
     */
    private fun predictLocationPattern(
        history: List<LocationRecord>,
        currentLocation: LocationRecord
    ): PredictedLocation? {
        // 查找相似时间的历史位置
        val currentHour = Calendar.getInstance().apply {
            timeInMillis = currentLocation.timestamp
        }.get(Calendar.HOUR_OF_DAY)

        val similarTimeLocations = history.filter { record ->
            val recordHour = Calendar.getInstance().apply {
                timeInMillis = record.timestamp
            }.get(Calendar.HOUR_OF_DAY)
            kotlin.math.abs(recordHour - currentHour) <= 1
        }

        if (similarTimeLocations.isEmpty()) return null

        // 计算平均位置
        val avgLat = similarTimeLocations.map { it.latitude }.average()
        val avgLng = similarTimeLocations.map { it.longitude }.average()

        // 找到最近的命名位置
        val namedLocation = similarTimeLocations
            .sortedBy {
                kotlin.math.abs(it.latitude - avgLat) + kotlin.math.abs(it.longitude - avgLng)
            }
            .firstOrNull { it.locationName != null }

        return PredictedLocation(
            latitude = avgLat,
            longitude = avgLng,
            locationName = namedLocation?.locationName,
            estimatedArrivalTime = System.currentTimeMillis() + 10 * 60 * 1000,
            confidence = 0.55f
        )
    }

    /**
     * 获取常去地点
     */
    fun getFrequentLocations(): List<FrequentLocation> {
        val history = _locationHistory.value

        // 按位置名称分组
        val namedLocations = history.filter { it.locationName != null }
            .groupBy { it.locationName!! }

        return namedLocations.map { (name, records) ->
            FrequentLocation(
                name = name,
                visitCount = records.size,
                lastVisit = records.maxOf { it.timestamp },
                averageLatitude = records.map { it.latitude }.average(),
                averageLongitude = records.map { it.longitude }.average()
            )
        }.sortedByDescending { it.visitCount }
    }

    data class FrequentLocation(
        val name: String,
        val visitCount: Int,
        val lastVisit: Long,
        val averageLatitude: Double,
        val averageLongitude: Double
    )
}

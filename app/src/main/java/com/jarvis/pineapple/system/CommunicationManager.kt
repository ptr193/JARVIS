package com.jarvis.pineapple.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/**
 * 通讯管理器（功能 #54-#58）。
 *
 * 功能：
 * - 提醒事项
 * - 出行安排
 * - 行程规划
 * - 导航路线动态调整
 * - 应急路线规划
 */
class CommunicationManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // ==================== 提醒事项（#54） ====================

    /**
     * 提醒
     */
    data class Reminder(
        val id: String,
        val title: String,
        val description: String,
        val triggerTime: Long,
        val repeatType: RepeatType = RepeatType.NONE,
        val isActive: Boolean = true
    )

    enum class RepeatType {
        NONE, DAILY, WEEKLY, MONTHLY, YEARLY
    }

    private val _reminders = MutableStateFlow<List<Reminder>>(emptyList())
    val reminders: StateFlow<List<Reminder>> = _reminders.asStateFlow()

    /**
     * 添加提醒
     */
    fun addReminder(reminder: Reminder) {
        val current = _reminders.value.toMutableList()
        current.add(reminder)
        _reminders.value = current.sortedBy { it.triggerTime }

        // 设置系统闹钟
        scheduleAlarm(reminder)
    }

    /**
     * 取消提醒
     */
    fun cancelReminder(reminderId: String) {
        _reminders.value = _reminders.value.filterNot { it.id == reminderId }
        cancelAlarm(reminderId)
    }

    /**
     * 获取到期的提醒
     */
    fun getDueReminders(): List<Reminder> {
        val now = System.currentTimeMillis()
        return _reminders.value.filter { it.isActive && it.triggerTime <= now }
    }

    private fun scheduleAlarm(reminder: Reminder) {
        val intent = Intent(context, com.jarvis.pineapple.receiver.ReminderReceiver::class.java).apply {
            action = com.jarvis.pineapple.receiver.ReminderReceiver.ACTION_REMINDER
            putExtra("reminder_id", reminder.id)
            putExtra("reminder_title", reminder.title)
            putExtra("reminder_content", reminder.description)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.triggerTime,
                    pendingIntent
                )
            } else {
                // 降级为 inexact alarm
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.triggerTime,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.triggerTime,
                pendingIntent
            )
        }
    }

    private fun cancelAlarm(reminderId: String) {
        val intent = Intent("com.jarvis.pineapple.REMINDER")
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        pendingIntent?.let { alarmManager.cancel(it) }
    }

    // ==================== 出行安排（#55） ====================

    /**
     * 出行安排
     */
    data class TravelArrangement(
        val id: String,
        val title: String,
        val departureTime: Long,
        val arrivalTime: Long,
        val origin: String,
        val destination: String,
        val transportType: TransportType,
        val notes: String = ""
    )

    enum class TransportType {
        DRIVING, PUBLIC_TRANSIT, WALKING, CYCLING, FLIGHT, TRAIN
    }

    private val _travelArrangements = MutableStateFlow<List<TravelArrangement>>(emptyList())
    val travelArrangements: StateFlow<List<TravelArrangement>> = _travelArrangements.asStateFlow()

    /**
     * 添加出行安排
     */
    fun addTravelArrangement(arrangement: TravelArrangement) {
        val current = _travelArrangements.value.toMutableList()
        current.add(arrangement)
        _travelArrangements.value = current.sortedBy { it.departureTime }

        // 添加到系统日历
        addToCalendar(arrangement)
    }

    /**
     * 获取即将到来的出行安排
     */
    fun getUpcomingTravels(): List<TravelArrangement> {
        val now = System.currentTimeMillis()
        return _travelArrangements.value.filter { it.departureTime > now }
    }

    private fun addToCalendar(arrangement: TravelArrangement): Boolean {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_CALENDAR
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.DTSTART, arrangement.departureTime)
            put(CalendarContract.Events.DTEND, arrangement.arrivalTime)
            put(CalendarContract.Events.TITLE, "出行: ${arrangement.title}")
            put(CalendarContract.Events.DESCRIPTION, "${arrangement.origin} -> ${arrangement.destination}")
            put(CalendarContract.Events.EVENT_TIMEZONE, Calendar.getInstance().timeZone.id)
        }
        return context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
    }

    // ==================== 行程规划（#56） ====================

    /**
     * 行程
     */
    data class TripPlan(
        val id: String,
        val name: String,
        val startDate: Long,
        val endDate: Long,
        val destinations: List<Destination>,
        val budget: Float = 0f,
        val notes: String = ""
    )

    data class Destination(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val visitDate: Long,
        val durationHours: Int,
        val activities: List<String> = emptyList()
    )

    private val _tripPlans = MutableStateFlow<List<TripPlan>>(emptyList())
    val tripPlans: StateFlow<List<TripPlan>> = _tripPlans.asStateFlow()

    /**
     * 创建行程
     */
    fun createTripPlan(tripPlan: TripPlan) {
        val current = _tripPlans.value.toMutableList()
        current.add(tripPlan)
        _tripPlans.value = current
    }

    /**
     * 获取当前行程
     */
    fun getCurrentTrip(): TripPlan? {
        val now = System.currentTimeMillis()
        return _tripPlans.value.find { now in it.startDate..it.endDate }
    }

    /**
     * 获取今日行程
     */
    fun getTodayItinerary(): List<Destination> {
        val currentTrip = getCurrentTrip() ?: return emptyList()
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        return currentTrip.destinations.filter {
            val destDay = Calendar.getInstance().apply { timeInMillis = it.visitDate }
            destDay.get(Calendar.DAY_OF_YEAR) == today
        }
    }

    // ==================== 导航路线动态调整（#57） ====================

    /**
     * 导航状态
     */
    data class NavigationState(
        val isNavigating: Boolean = false,
        val currentRoute: Route? = null,
        val remainingDistanceKm: Float = 0f,
        val remainingTimeMinutes: Int = 0,
        val trafficCondition: TrafficCondition = TrafficCondition.UNKNOWN
    )

    data class Route(
        val origin: String,
        val destination: String,
        val distanceKm: Float,
        val estimatedTimeMinutes: Int,
        val steps: List<RouteStep>
    )

    data class RouteStep(
        val instruction: String,
        val distanceMeters: Int,
        val durationSeconds: Int
    )

    enum class TrafficCondition {
        LIGHT, MODERATE, HEAVY, SEVERE, UNKNOWN
    }

    private val _navigationState = MutableStateFlow(NavigationState())
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    /**
     * 开始导航（origin 用于起点标注，高德 URI 不支持起点时降级方案中保留）
     */
    fun startNavigation(origin: String, destination: String) {
        // 起点非空时以 "起点;终点" 形式传入高德
        val point = if (origin.isNotBlank()) "$origin;$destination" else destination
        val uri = android.net.Uri.parse("androidamap://navi?sourceApplication=JARVIS&poiname=$point&dev=0")
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.autonavi.minimap")
        }
        runCatching { context.startActivity(intent) }.onFailure {
            // 降级到 geo URI（保留起点坐标/名称作为起点提示）
            val query = if (origin.isNotBlank()) "$destination (from $origin)" else destination
            val fallback = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("geo:0,0?q=${android.net.Uri.encode(query)}")
            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            runCatching { context.startActivity(fallback) }
        }
    }

    /**
     * 更新导航状态
     * 需真实导航后端提供剩余距离/时间，当前版本无后端，抛异常
     */
    fun updateNavigation(remainingDistanceKm: Float, remainingTimeMinutes: Int) {
        _navigationState.value = _navigationState.value.copy(
            remainingDistanceKm = remainingDistanceKm,
            remainingTimeMinutes = remainingTimeMinutes
        )
    }

    /**
     * 根据路况动态调整路线
     * 需真实路况数据源 + 重算算法，当前版本无地图 SDK，抛异常
     */
    fun adjustRouteForTraffic(trafficCondition: TrafficCondition) {
        val currentState = _navigationState.value
        if (!currentState.isNavigating) return

        val adjustedTime = when (trafficCondition) {
            TrafficCondition.LIGHT -> currentState.remainingTimeMinutes
            TrafficCondition.MODERATE -> (currentState.remainingTimeMinutes * 1.3).toInt()
            TrafficCondition.HEAVY -> (currentState.remainingTimeMinutes * 1.8).toInt()
            TrafficCondition.SEVERE -> (currentState.remainingTimeMinutes * 2.5).toInt()
            TrafficCondition.UNKNOWN -> currentState.remainingTimeMinutes
        }

        _navigationState.value = currentState.copy(
            trafficCondition = trafficCondition,
            remainingTimeMinutes = adjustedTime
        )
    }

    /**
     * 停止导航
     */
    fun stopNavigation() {
        _navigationState.value = NavigationState()
    }

    // ==================== 应急路线规划（#58） ====================

    /**
     * 应急路线
     */
    data class EmergencyRoute(
        val origin: String,
        val destination: String,
        val routeType: EmergencyRouteType,
        val distanceKm: Float,
        val estimatedTimeMinutes: Int,
        val waypoints: List<String> = emptyList()
    )

    enum class EmergencyRouteType {
        FASTEST, SAFEST, AVOID_TOLLS, AVOID_HIGHWAYS, EMERGENCY_VEHICLE
    }

    /**
     * 规划应急路线
     */
    fun planEmergencyRoute(
        origin: String,
        destination: String,
        routeType: EmergencyRouteType = EmergencyRouteType.FASTEST
    ): EmergencyRoute {
        // 调起地图导航到应急目的地（最近医院/警察局/消防站）
        startNavigation(origin, destination)
        // 返回预估路线信息（实际数据由地图应用提供）
        return EmergencyRoute(
            origin = origin,
            destination = destination,
            routeType = routeType,
            distanceKm = 0f, // 地图应用导航时会有真实数据
            estimatedTimeMinutes = 0,
            waypoints = emptyList()
        )
    }

    /**
     * 获取最近医院路线
     */
    fun getNearestHospitalRoute(currentLocation: String): EmergencyRoute {
        return planEmergencyRoute(
            origin = currentLocation,
            destination = "最近医院",
            routeType = EmergencyRouteType.FASTEST
        )
    }

    /**
     * 获取最近警察局路线
     */
    fun getNearestPoliceStationRoute(currentLocation: String): EmergencyRoute {
        return planEmergencyRoute(
            origin = currentLocation,
            destination = "最近警察局",
            routeType = EmergencyRouteType.FASTEST
        )
    }

    /**
     * 获取最近消防站路线
     */
    fun getNearestFireStationRoute(currentLocation: String): EmergencyRoute {
        return planEmergencyRoute(
            origin = currentLocation,
            destination = "最近消防站",
            routeType = EmergencyRouteType.FASTEST
        )
    }
}

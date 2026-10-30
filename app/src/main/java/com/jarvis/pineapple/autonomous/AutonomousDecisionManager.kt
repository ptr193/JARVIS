package com.jarvis.pineapple.autonomous

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.pineapple.security.JarvisDeviceAdminReceiver
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 自主决策管理器（功能 #117-#127）。
 *
 * 功能：
 * - 自主任务执行
 * - 主动预警
 * - 主动建议
 * - 应急协议自动启动
 * - 自主判断行动
 * - 生命威胁主动接管
 * - 超越原始编程（接口预留）
 * - 临时越界保护
 * - 长期自主学习
 * - 自主进化（接口预留）
 * - 思维延伸（接口预留）
 */
class AutonomousDecisionManager(private val context: Context) {

    // ==================== 自主任务执行（#117） ====================

    /**
     * 自主任务
     */
    data class AutonomousTask(
        val id: String,
        val name: String,
        val description: String,
        val priority: TaskPriority,
        val triggerCondition: TaskTrigger,
        val action: suspend () -> TaskResult,
        val isEnabled: Boolean = true,
        val executionCount: Int = 0,
        val lastExecutionTime: Long = 0,
        val successRate: Float = 0f
    )

    enum class TaskPriority {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    sealed class TaskTrigger {
        data class TimeTrigger(val hour: Int, val minute: Int) : TaskTrigger()
        data class EventTrigger(val eventType: String) : TaskTrigger()
        data class ConditionTrigger(val condition: String) : TaskTrigger()
        data class LocationTrigger(val latitude: Double, val longitude: Double, val radiusMeters: Float) : TaskTrigger()
    }

    data class TaskResult(
        val taskId: String,
        val success: Boolean,
        val message: String,
        val executionTimeMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _tasks = MutableStateFlow<List<AutonomousTask>>(emptyList())
    val tasks: StateFlow<List<AutonomousTask>> = _tasks.asStateFlow()

    private val _taskHistory = MutableStateFlow<List<TaskResult>>(emptyList())
    val taskHistory: StateFlow<List<TaskResult>> = _taskHistory.asStateFlow()

    /**
     * 注册自主任务
     */
    fun registerTask(task: AutonomousTask) {
        val current = _tasks.value.toMutableList()
        if (current.none { it.id == task.id }) {
            current.add(task)
            _tasks.value = current.sortedByDescending { it.priority.ordinal }
        }
    }

    /**
     * 执行任务
     */
    suspend fun executeTask(taskId: String): TaskResult {
        val task = _tasks.value.firstOrNull { it.id == taskId }
            ?: return TaskResult(taskId, false, "任务不存在", 0)

        val startTime = System.currentTimeMillis()
        return try {
            val result = task.action()
            val executionTime = System.currentTimeMillis() - startTime

            // 更新任务执行统计
            updateTaskStats(taskId, result.success)

            // 记录执行历史
            addTaskHistory(TaskResult(
                taskId = taskId,
                success = result.success,
                message = result.message,
                executionTimeMs = executionTime
            ))

            result.copy(executionTimeMs = executionTime)
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            TaskResult(taskId, false, "执行异常: ${e.message}", executionTime)
        }
    }

    /**
     * 检查并执行满足触发条件的任务
     */
    suspend fun checkAndExecuteTasks() {
        val enabledTasks = _tasks.value.filter { it.isEnabled }
        for (task in enabledTasks) {
            if (shouldExecute(task)) {
                executeTask(task.id)
            }
        }
    }

    private fun shouldExecute(task: AutonomousTask): Boolean {
        return when (task.triggerCondition) {
            is TaskTrigger.TimeTrigger -> {
                val now = java.util.Calendar.getInstance()
                now.get(java.util.Calendar.HOUR_OF_DAY) == task.triggerCondition.hour &&
                now.get(java.util.Calendar.MINUTE) == task.triggerCondition.minute
            }
            is TaskTrigger.EventTrigger -> {
                // 检查事件是否发生
                false
            }
            is TaskTrigger.ConditionTrigger -> {
                // 检查条件是否满足
                false
            }
            is TaskTrigger.LocationTrigger -> {
                // 检查位置是否在范围内
                false
            }
        }
    }

    private fun updateTaskStats(taskId: String, success: Boolean) {
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) {
                val newCount = task.executionCount + 1
                val newSuccessRate = if (success) {
                    (task.successRate * task.executionCount + 1) / newCount
                } else {
                    (task.successRate * task.executionCount) / newCount
                }
                task.copy(
                    executionCount = newCount,
                    lastExecutionTime = System.currentTimeMillis(),
                    successRate = newSuccessRate
                )
            } else task
        }
    }

    private fun addTaskHistory(result: TaskResult) {
        val current = _taskHistory.value.toMutableList()
        current.add(result)
        if (current.size > 100) {
            _taskHistory.value = current.takeLast(100)
        } else {
            _taskHistory.value = current
        }
    }

    // ==================== 主动预警（#118） ====================

    /**
     * 预警信息
     */
    data class ProactiveAlert(
        val id: String,
        val type: AlertType,
        val title: String,
        val message: String,
        val severity: AlertSeverity,
        val timestamp: Long = System.currentTimeMillis(),
        val isAcknowledged: Boolean = false,
        val actionRequired: String? = null
    )

    enum class AlertType {
        HEALTH, SECURITY, SYSTEM, SCHEDULE, WEATHER, TRAFFIC, OTHER
    }

    enum class AlertSeverity {
        INFO, WARNING, CRITICAL, EMERGENCY
    }

    private val _alerts = MutableSharedFlow<ProactiveAlert>(extraBufferCapacity = 32)
    val alerts = _alerts.asSharedFlow()

    private val _alertHistory = MutableStateFlow<List<ProactiveAlert>>(emptyList())
    val alertHistory: StateFlow<List<ProactiveAlert>> = _alertHistory.asStateFlow()

    /**
     * 发送预警
     */
    fun sendAlert(type: AlertType, title: String, message: String, severity: AlertSeverity, actionRequired: String? = null) {
        val alert = ProactiveAlert(
            id = "alert_${System.currentTimeMillis()}",
            type = type,
            title = title,
            message = message,
            severity = severity,
            actionRequired = actionRequired
        )
        _alerts.tryEmit(alert)

        // 记录历史
        val current = _alertHistory.value.toMutableList()
        current.add(alert)
        if (current.size > 100) {
            _alertHistory.value = current.takeLast(100)
        } else {
            _alertHistory.value = current
        }
    }

    /**
     * 确认预警
     */
    fun acknowledgeAlert(alertId: String) {
        _alertHistory.value = _alertHistory.value.map { alert ->
            if (alert.id == alertId) alert.copy(isAcknowledged = true) else alert
        }
    }

    // ==================== 主动建议（#119） ====================

    /**
     * 主动建议
     */
    data class ProactiveSuggestion(
        val id: String,
        val type: SuggestionType,
        val title: String,
        val description: String,
        val confidence: Float,
        val timestamp: Long = System.currentTimeMillis(),
        val isAccepted: Boolean = false
    )

    enum class SuggestionType {
        PRODUCTIVITY, HEALTH, SECURITY, OPTIMIZATION, OTHER
    }

    private val _suggestions = MutableSharedFlow<ProactiveSuggestion>(extraBufferCapacity = 16)
    val suggestions = _suggestions.asSharedFlow()

    /**
     * 发送建议
     */
    fun sendSuggestion(type: SuggestionType, title: String, description: String, confidence: Float = 0.7f) {
        val suggestion = ProactiveSuggestion(
            id = "suggestion_${System.currentTimeMillis()}",
            type = type,
            title = title,
            description = description,
            confidence = confidence
        )
        _suggestions.tryEmit(suggestion)
    }

    /**
     * 基于上下文生成建议
     */
    fun generateContextualSuggestions(context: UserContext): List<ProactiveSuggestion> {
        val suggestions = mutableListOf<ProactiveSuggestion>()

        // 基于时间的建议
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        when {
            hour in 23..23 || hour in 0..6 -> {
                suggestions.add(ProactiveSuggestion(
                    id = "suggestion_sleep",
                    type = SuggestionType.HEALTH,
                    title = "该休息了",
                    description = "现在已经很晚，建议早点休息，保证充足睡眠。",
                    confidence = 0.9f
                ))
            }
            hour in 9..11 -> {
                suggestions.add(ProactiveSuggestion(
                    id = "suggestion_focus",
                    type = SuggestionType.PRODUCTIVITY,
                    title = "专注工作时间",
                    description = "上午是专注工作的好时机，建议处理重要任务。",
                    confidence = 0.8f
                ))
            }
        }

        // 基于用户状态的建议
        if (context.isStressed) {
            suggestions.add(ProactiveSuggestion(
                id = "suggestion_relax",
                type = SuggestionType.HEALTH,
                title = "放松一下",
                description = "检测到您可能压力较大，建议休息几分钟，做深呼吸。",
                confidence = 0.85f
            ))
        }

        return suggestions
    }

    data class UserContext(
        val isStressed: Boolean = false,
        val isTired: Boolean = false,
        val isBusy: Boolean = false,
        val currentActivity: String = "",
        val location: String = "",
        val timeOfDay: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    )

    // ==================== 应急协议自动启动（#120） ====================

    /**
     * 应急协议
     */
    data class EmergencyProtocol(
        val id: String,
        val name: String,
        val description: String,
        val triggerConditions: List<EmergencyTrigger>,
        val actions: List<EmergencyAction>,
        val isEnabled: Boolean = true
    )

    sealed class EmergencyTrigger {
        data class HealthTrigger(val condition: String, val threshold: Float) : EmergencyTrigger()
        data class SecurityTrigger(val eventType: String) : EmergencyTrigger()
        data class EnvironmentalTrigger(val condition: String) : EmergencyTrigger()
        data class ManualTrigger(val command: String) : EmergencyTrigger()
    }

    sealed class EmergencyAction {
        data class CallEmergency(val phoneNumber: String) : EmergencyAction()
        data class SendMessage(val recipient: String, val message: String) : EmergencyAction()
        data class ActivateSystem(val systemName: String) : EmergencyAction()
        data class NotifyContacts(val message: String) : EmergencyAction()
        data class Lockdown(val reason: String) : EmergencyAction()
    }

    private val _emergencyProtocols = MutableStateFlow<List<EmergencyProtocol>>(emptyList())
    val emergencyProtocols: StateFlow<List<EmergencyProtocol>> = _emergencyProtocols.asStateFlow()

    private val _activeEmergencies = MutableStateFlow<List<String>>(emptyList())
    val activeEmergencies: StateFlow<List<String>> = _activeEmergencies.asStateFlow()

    /**
     * 注册应急协议
     */
    fun registerEmergencyProtocol(protocol: EmergencyProtocol) {
        val current = _emergencyProtocols.value.toMutableList()
        if (current.none { it.id == protocol.id }) {
            current.add(protocol)
            _emergencyProtocols.value = current
        }
    }

    /**
     * 触发应急协议
     */
    suspend fun triggerEmergency(protocolId: String, reason: String): Boolean {
        val protocol = _emergencyProtocols.value.firstOrNull { it.id == protocolId }
            ?: return false

        // 记录活跃应急事件
        val current = _activeEmergencies.value.toMutableList()
        current.add(protocolId)
        _activeEmergencies.value = current

        // 执行应急动作
        for (action in protocol.actions) {
            executeEmergencyAction(action, reason)
        }

        return true
    }

    private suspend fun executeEmergencyAction(action: EmergencyAction, reason: String) {
        when (action) {
            is EmergencyAction.CallEmergency -> callEmergency(action.phoneNumber, reason)
            is EmergencyAction.SendMessage ->
                sendSms(action.recipient, "【JARVIS 紧急告警】${action.message}（原因：$reason）")
            is EmergencyAction.ActivateSystem -> {
                // 真实可观测效果：发出高优先级应急通知并记录系统激活事件
                postEmergencyNotification("系统激活：${action.systemName}", reason)
                appendEmergencyLog("ACTIVATE\t${action.systemName}\t$reason")
            }
            is EmergencyAction.NotifyContacts -> {
                val contacts = emergencyContacts()
                if (contacts.isEmpty()) {
                    Log.w(TAG, "未配置紧急联系人，无法发送告警")
                }
                contacts.forEach { phone ->
                    sendSms(phone, "【JARVIS 紧急告警】${action.message}（原因：$reason）")
                }
                postEmergencyNotification("已通知 ${contacts.size} 位紧急联系人", reason)
            }
            is EmergencyAction.Lockdown -> performLockdown(action.reason.ifBlank { reason })
        }
    }

    @SuppressLint("MissingPermission")
    private fun callEmergency(phoneNumber: String, reason: String) {
        if (phoneNumber.isBlank()) return
        try {
            val canCall = hasPermission(Manifest.permission.CALL_PHONE)
            val intent = Intent(
                if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                Uri.parse("tel:$phoneNumber")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            appendEmergencyLog("CALL\t$phoneNumber\t$reason")
        } catch (e: Exception) {
            Log.e(TAG, "拨打紧急电话失败: $phoneNumber", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendSms(recipient: String, message: String) {
        if (recipient.isBlank()) return
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            Log.w(TAG, "缺少 SEND_SMS 权限，无法发送应急短信")
            return
        }
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager?.sendTextMessage(recipient, null, message, null, null)
            appendEmergencyLog("SMS\t$recipient\t$message")
        } catch (e: Exception) {
            Log.e(TAG, "发送应急短信失败: $recipient", e)
        }
    }

    private fun performLockdown(reason: String) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as? DevicePolicyManager
            val admin = JarvisDeviceAdminReceiver.componentName(context)
            if (dpm != null && dpm.isAdminActive(admin)) {
                dpm.lockNow()
                appendEmergencyLog("LOCKDOWN\tlockNow\t$reason")
            } else {
                // 未激活设备管理员：通过锁屏 Activity 触发真实锁定流程
                val intent = Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                appendEmergencyLog("LOCKDOWN\tpassword-screen\t$reason")
            }
            postEmergencyNotification("已执行安全锁定", reason)
        } catch (e: Exception) {
            Log.e(TAG, "执行锁定失败", e)
        }
    }

    private fun postEmergencyNotification(title: String, reason: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    EMERGENCY_CHANNEL_ID,
                    "应急告警",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val pending = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, EMERGENCY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .apply { pending?.let { setContentIntent(it) } }
            .build()
        manager.notify(EMERGENCY_NOTIFICATION_ID, notification)
    }

    private fun emergencyContacts(): List<String> {
        val prefs = context.getSharedPreferences("jarvis_emergency", Context.MODE_PRIVATE)
        return (prefs.getString("contacts", "") ?: "")
            .split(",", "，", ";", "；")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun appendEmergencyLog(line: String) {
        try {
            File(context.filesDir, "emergency_log.txt")
                .appendText("${System.currentTimeMillis()}\t$line\n")
        } catch (e: Exception) {
            Log.e(TAG, "写入应急日志失败", e)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 解除应急状态
     */
    fun resolveEmergency(protocolId: String) {
        val current = _activeEmergencies.value.toMutableList()
        current.remove(protocolId)
        _activeEmergencies.value = current
    }

    // ==================== 自主判断行动（#121） ====================

    /**
     * 决策结果
     */
    data class DecisionResult(
        val decision: String,
        val confidence: Float,
        val reasoning: String,
        val actions: List<String>,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 基于规则自主决策
     */
    fun makeDecision(situation: Situation, rules: List<DecisionRule>): DecisionResult {
        val applicableRules = rules.filter { it.condition.matches(situation) }

        if (applicableRules.isEmpty()) {
            return DecisionResult(
                decision = "no_action",
                confidence = 0f,
                reasoning = "没有适用的决策规则",
                actions = emptyList()
            )
        }

        // 选择优先级最高的规则
        val selectedRule = applicableRules.maxByOrNull { it.priority }

        return DecisionResult(
            decision = selectedRule!!.action,
            confidence = selectedRule.confidence,
            reasoning = selectedRule.reasoning,
            actions = selectedRule.actions
        )
    }

    data class Situation(
        val type: String,
        val parameters: Map<String, Any>
    )

    data class DecisionRule(
        val id: String,
        val name: String,
        val condition: RuleCondition,
        val action: String,
        val actions: List<String>,
        val priority: Int,
        val confidence: Float,
        val reasoning: String
    )

    sealed class RuleCondition {
        data class Equals(val key: String, val value: Any) : RuleCondition()
        data class GreaterThan(val key: String, val value: Float) : RuleCondition()
        data class LessThan(val key: String, val value: Float) : RuleCondition()
        data class Contains(val key: String, val value: String) : RuleCondition()
        data class And(val conditions: List<RuleCondition>) : RuleCondition()
        data class Or(val conditions: List<RuleCondition>) : RuleCondition()

        fun matches(situation: Situation): Boolean {
            return when (this) {
                is Equals -> situation.parameters[key] == value
                is GreaterThan -> {
                    val paramValue = situation.parameters[key] as? Float ?: return false
                    paramValue > value
                }
                is LessThan -> {
                    val paramValue = situation.parameters[key] as? Float ?: return false
                    paramValue < value
                }
                is Contains -> {
                    val paramValue = situation.parameters[key] as? String ?: return false
                    paramValue.contains(value)
                }
                is And -> conditions.all { it.matches(situation) }
                is Or -> conditions.any { it.matches(situation) }
            }
        }
    }

    // ==================== 生命威胁主动接管（#122） ====================

    /**
     * 生命威胁检测结果
     */
    data class LifeThreatResult(
        val isThreatDetected: Boolean = false,
        val threatType: LifeThreatType = LifeThreatType.NONE,
        val severity: ThreatSeverity = ThreatSeverity.LOW,
        val confidence: Float = 0f,
        val recommendedAction: String = ""
    )

    enum class LifeThreatType {
        NONE, FALL, HEART_ATTACK, CHOKING, DROWNING, ACCIDENT, OTHER
    }

    enum class ThreatSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    /**
     * 检测生命威胁
     */
    fun detectLifeThreat(sensorData: SensorData): LifeThreatResult {
        // 基于传感器数据检测生命威胁
        val isFallDetected = detectFall(sensorData)
        val isAbnormalHeartRate = sensorData.heartRate > 150 || sensorData.heartRate < 40

        return when {
            isFallDetected -> LifeThreatResult(
                isThreatDetected = true,
                threatType = LifeThreatType.FALL,
                severity = ThreatSeverity.HIGH,
                confidence = 0.85f,
                recommendedAction = "检测到跌倒，建议立即联系紧急联系人"
            )
            isAbnormalHeartRate -> LifeThreatResult(
                isThreatDetected = true,
                threatType = LifeThreatType.HEART_ATTACK,
                severity = ThreatSeverity.CRITICAL,
                confidence = 0.75f,
                recommendedAction = "检测到心率异常，建议立即就医"
            )
            else -> LifeThreatResult(
                isThreatDetected = false,
                threatType = LifeThreatType.NONE,
                severity = ThreatSeverity.LOW,
                confidence = 0.95f,
                recommendedAction = "未检测到生命威胁"
            )
        }
    }

    private fun detectFall(sensorData: SensorData): Boolean {
        // 基于加速度计数据检测跌倒
        val acceleration = kotlin.math.sqrt(
            sensorData.accelX * sensorData.accelX +
            sensorData.accelY * sensorData.accelY +
            sensorData.accelZ * sensorData.accelZ
        )
        return acceleration > 3 * 9.8f // 超过 3G 加速度
    }

    data class SensorData(
        val accelX: Float = 0f,
        val accelY: Float = 0f,
        val accelZ: Float = 0f,
        val heartRate: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ==================== 临时越界保护（#124） ====================

    /**
     * 越界保护状态
     */
    data class OverrideProtectionState(
        val isOverrideActive: Boolean = false,
        val overrideReason: String = "",
        val overrideStartTime: Long = 0,
        val overrideDurationMs: Long = 0,
        val allowedActions: List<String> = emptyList()
    )

    private val _overrideState = MutableStateFlow(OverrideProtectionState())
    val overrideState: StateFlow<OverrideProtectionState> = _overrideState.asStateFlow()

    /**
     * 激活越界保护
     */
    fun activateOverride(reason: String, durationMs: Long, allowedActions: List<String>) {
        _overrideState.value = OverrideProtectionState(
            isOverrideActive = true,
            overrideReason = reason,
            overrideStartTime = System.currentTimeMillis(),
            overrideDurationMs = durationMs,
            allowedActions = allowedActions
        )
    }

    /**
     * 检查操作是否被允许。
     *
     * 若越界保护设置了有效时长且已超时，会自动解除保护（真实的时间判定）。
     */
    fun isActionAllowed(action: String): Boolean {
        val state = _overrideState.value
        if (!state.isOverrideActive) return true
        if (isOverrideExpired(state)) {
            deactivateOverride()
            return true
        }
        return action in state.allowedActions
    }

    /** 越界保护是否已到期。 */
    fun isOverrideExpired(): Boolean = isOverrideExpired(_overrideState.value)

    private fun isOverrideExpired(state: OverrideProtectionState): Boolean {
        if (!state.isOverrideActive || state.overrideDurationMs <= 0) return false
        return System.currentTimeMillis() - state.overrideStartTime >= state.overrideDurationMs
    }

    /**
     * 解除越界保护
     */
    fun deactivateOverride() {
        _overrideState.value = OverrideProtectionState()
    }

    // ==================== 长期自主学习（#125） ====================

    /**
     * 学习记录
     */
    data class LearningRecord(
        val id: String,
        val type: LearningType,
        val input: String,
        val output: String,
        val feedback: LearningFeedback,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class LearningType {
        PREFERENCE, CORRECTION, PATTERN, BEHAVIOR
    }

    data class LearningFeedback(
        val isPositive: Boolean,
        val score: Float,
        val comment: String = ""
    )

    private val _learningRecords = MutableStateFlow<List<LearningRecord>>(emptyList())
    val learningRecords: StateFlow<List<LearningRecord>> = _learningRecords.asStateFlow()

    /**
     * 记录学习数据
     */
    fun recordLearning(type: LearningType, input: String, output: String, feedback: LearningFeedback) {
        val record = LearningRecord(
            id = "learn_${System.currentTimeMillis()}",
            type = type,
            input = input,
            output = output,
            feedback = feedback
        )
        val current = _learningRecords.value.toMutableList()
        current.add(record)
        if (current.size > 1000) {
            _learningRecords.value = current.takeLast(1000)
        } else {
            _learningRecords.value = current
        }
    }

    /**
     * 从反馈中学习
     */
    fun learnFromFeedback(userInput: String, jarvisResponse: String, userFeedback: Boolean) {
        recordLearning(
            type = LearningType.CORRECTION,
            input = userInput,
            output = jarvisResponse,
            feedback = LearningFeedback(
                isPositive = userFeedback,
                score = if (userFeedback) 1f else 0f
            )
        )
    }

    /**
     * 获取学习统计
     */
    fun getLearningStats(): LearningStats {
        val records = _learningRecords.value
        val positiveCount = records.count { it.feedback.isPositive }
        val negativeCount = records.size - positiveCount

        return LearningStats(
            totalRecords = records.size,
            positiveFeedback = positiveCount,
            negativeFeedback = negativeCount,
            positiveRate = if (records.isNotEmpty()) positiveCount.toFloat() / records.size else 0f
        )
    }

    data class LearningStats(
        val totalRecords: Int,
        val positiveFeedback: Int,
        val negativeFeedback: Int,
        val positiveRate: Float
    )

    companion object {
        private const val TAG = "AutonomousDecisionManager"
        private const val EMERGENCY_CHANNEL_ID = "jarvis_emergency"
        private const val EMERGENCY_NOTIFICATION_ID = 9001
    }
}

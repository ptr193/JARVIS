package com.jarvis.pineapple.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.jarvis.pineapple.security.CryptoManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "HealthMonitor"

/**
 * 健康监测器（功能 #59-#71）。
 *
 * 功能：
 * - 生命体征监测（心率、血压、体温）
 * - 疲劳监测与提醒
 * - 认知负荷评估
 * - 医疗提醒
 * - 用药管理
 * - 健康趋势分析
 * - 远程医疗会诊
 */
class HealthMonitor(
    private val context: Context,
    private val cryptoManager: CryptoManager
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val ppgMonitor = PpgMonitor(context)

    // ==================== 生命体征数据 ====================

    private val _vitalSigns = MutableStateFlow(VitalSigns())
    val vitalSigns: StateFlow<VitalSigns> = _vitalSigns.asStateFlow()

    /**
     * 生命体征数据
     */
    data class VitalSigns(
        val heartRate: Int = 0,                    // BPM
        val bloodPressureSystolic: Int = 0,        // 收缩压 mmHg
        val bloodPressureDiastolic: Int = 0,       // 舒张压 mmHg
        val temperature: Float = 0f,               // 体温 ℃
        val spo2: Int = 0,                         // 血氧饱和度 %
        val respiratoryRate: Int = 0,              // 呼吸频率 次/分钟
        val stressLevel: Int = 0,                  // 压力水平 0-100
        val timestamp: Long = System.currentTimeMillis(),
        val source: String = ""                    // 数据来源（传感器/设备标识）
    )

    // ==================== 心率监测（#60） ====================

    /**
     * 心率测量：优先系统心率传感器，其次摄像头 PPG（[PpgMonitor]）。
     *
     * @return 心率（BPM），失败返回 0
     */
    suspend fun measureHeartRatePPG(): Int {
        val heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (heartRateSensor != null) {
            val reading = readSystemHeartRate(heartRateSensor)
            if (reading > 0) return reading
        }
        return ppgMonitor.measure().bpm
    }

    private suspend fun readSystemHeartRate(sensor: Sensor): Int =
        kotlinx.coroutines.withTimeoutOrNull(5_000L) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (event.values.isEmpty()) return
                        val bpm = event.values[0].toInt()
                        if (bpm in 20..250) {
                            sensorManager.unregisterListener(this)
                            if (continuation.isActive) continuation.resume(bpm)
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                continuation.invokeOnCancellation { sensorManager.unregisterListener(listener) }
            }
        } ?: 0

    /**
     * 蓝牙心率带数据接入
     */
    fun connectBluetoothHeartRateMonitor(deviceAddress: String): Boolean {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter = bluetoothManager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                Log.w("HealthMonitor", "Bluetooth not available or disabled")
                return false
            }

            val device = adapter.getRemoteDevice(deviceAddress)
            val gatt = device.connectGatt(context, false, object : android.bluetooth.BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
                    if (newState == android.bluetooth.BluetoothGatt.STATE_CONNECTED) {
                        Log.i("HealthMonitor", "Connected to heart rate monitor: $deviceAddress")
                        gatt.discoverServices()
                    }
                }

                override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt, status: Int) {
                    val service = gatt.getService(java.util.UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"))
                    val char = service?.getCharacteristic(java.util.UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb"))
                    if (char != null) {
                        gatt.setCharacteristicNotification(char, true)
                    }
                }

                override fun onCharacteristicChanged(gatt: android.bluetooth.BluetoothGatt, characteristic: android.bluetooth.BluetoothGattCharacteristic) {
                    val hr = characteristic.value?.get(0)?.toInt()?.and(0xFF) ?: 0
                    _vitalSigns.value = _vitalSigns.value.copy(heartRate = hr, source = "bluetooth_hrm")
                }
            })
            Log.i("HealthMonitor", "Connecting to heart rate monitor: $deviceAddress")
            true
        } catch (e: Exception) {
            Log.e("HealthMonitor", "Failed to connect heart rate monitor", e)
            false
        }
    }

    // ==================== 血压监测（#61） ====================

    /**
     * 蓝牙血压计数据接入
     */
    fun connectBluetoothBloodPressureMonitor(deviceAddress: String): Boolean {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter = bluetoothManager?.adapter
            if (adapter == null || !adapter.isEnabled) return false

            val device = adapter.getRemoteDevice(deviceAddress)
            device.connectGatt(context, false, object : android.bluetooth.BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
                    if (newState == android.bluetooth.BluetoothGatt.STATE_CONNECTED) {
                        Log.i("HealthMonitor", "Connected to blood pressure monitor: $deviceAddress")
                        gatt.discoverServices()
                    }
                }

                override fun onCharacteristicChanged(gatt: android.bluetooth.BluetoothGatt, characteristic: android.bluetooth.BluetoothGattCharacteristic) {
                    Log.i("HealthMonitor", "Blood pressure data received")
                }
            })
            true
        } catch (e: Exception) {
            Log.e("HealthMonitor", "Failed to connect blood pressure monitor", e)
            false
        }
    }

    /**
     * 记录血压数据
     */
    fun recordBloodPressure(systolic: Int, diastolic: Int) {
        _vitalSigns.value = _vitalSigns.value.copy(
            bloodPressureSystolic = systolic,
            bloodPressureDiastolic = diastolic
        )
        addHealthRecord(HealthRecord(
            type = "blood_pressure",
            value = "$systolic/$diastolic",
            unit = "mmHg",
            timestamp = System.currentTimeMillis()
        ))
    }

    // ==================== 体温监测（#62） ====================

    /**
     * 蓝牙体温计数据接入
     */
    fun connectBluetoothThermometer(deviceAddress: String): Boolean {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter = bluetoothManager?.adapter
            if (adapter == null || !adapter.isEnabled) return false

            val device = adapter.getRemoteDevice(deviceAddress)
            device.connectGatt(context, false, object : android.bluetooth.BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
                    if (newState == android.bluetooth.BluetoothGatt.STATE_CONNECTED) {
                        Log.i("HealthMonitor", "Connected to thermometer: $deviceAddress")
                        gatt.discoverServices()
                    }
                }
            })
            true
        } catch (e: Exception) {
            Log.e("HealthMonitor", "Failed to connect thermometer", e)
            false
        }
    }

    /**
     * 记录体温数据
     */
    fun recordTemperature(temperature: Float) {
        _vitalSigns.value = _vitalSigns.value.copy(temperature = temperature)
        addHealthRecord(HealthRecord(
            type = "temperature",
            value = "%.1f".format(temperature),
            unit = "°C",
            timestamp = System.currentTimeMillis()
        ))
    }

    // ==================== 疲劳监测（#63） ====================

    /**
     * 疲劳监测数据
     */
    data class FatigueData(
        val eyeAspectRatio: Float = 0f,            // 眼睛纵横比
        val blinkRate: Int = 0,                    // 眨眼频率
        val yawnCount: Int = 0,                    // 打哈欠次数
        val headTiltAngle: Float = 0f,             // 头部倾斜角度
        val fatigueLevel: FatigueLevel = FatigueLevel.NORMAL
    )

    enum class FatigueLevel {
        NORMAL,     // 正常
        MILD,       // 轻度疲劳
        MODERATE,   // 中度疲劳
        SEVERE      // 严重疲劳
    }

    private val _fatigueData = MutableStateFlow(FatigueData())
    val fatigueData: StateFlow<FatigueData> = _fatigueData.asStateFlow()

    /**
     * 分析疲劳状态
     */
    fun analyzeFatigue(eyeAspectRatio: Float, yawnCount: Int): FatigueLevel {
        val level = when {
            eyeAspectRatio < 0.15f || yawnCount >= 5 -> FatigueLevel.SEVERE
            eyeAspectRatio < 0.20f || yawnCount >= 3 -> FatigueLevel.MODERATE
            eyeAspectRatio < 0.25f || yawnCount >= 1 -> FatigueLevel.MILD
            else -> FatigueLevel.NORMAL
        }
        _fatigueData.value = _fatigueData.value.copy(
            eyeAspectRatio = eyeAspectRatio,
            yawnCount = yawnCount,
            fatigueLevel = level
        )
        return level
    }

    /**
     * 获取疲劳提醒
     */
    fun getFatigueAlert(): String? {
        return when (_fatigueData.value.fatigueLevel) {
            FatigueLevel.SEVERE -> "检测到严重疲劳，建议立即休息！"
            FatigueLevel.MODERATE -> "检测到中度疲劳，建议适当休息。"
            FatigueLevel.MILD -> "检测到轻度疲劳，请注意休息。"
            FatigueLevel.NORMAL -> null
        }
    }

    // ==================== 认知负荷评估（#64） ====================

    /**
     * 认知负荷数据
     */
    data class CognitiveLoadData(
        val reactionTimeMs: Long = 0,              // 反应时间
        val errorRate: Float = 0f,                 // 错误率
        val taskCompletionRate: Float = 0f,        // 任务完成率
        val cognitiveLoadLevel: CognitiveLoadLevel = CognitiveLoadLevel.LOW
    )

    enum class CognitiveLoadLevel {
        LOW, MEDIUM, HIGH, OVERLOAD
    }

    private val _cognitiveLoad = MutableStateFlow(CognitiveLoadData())
    val cognitiveLoad: StateFlow<CognitiveLoadData> = _cognitiveLoad.asStateFlow()

    /**
     * 评估认知负荷
     */
    fun assessCognitiveLoad(
        reactionTimeMs: Long,
        errorRate: Float,
        taskCompletionRate: Float
    ): CognitiveLoadLevel {
        val level = when {
            reactionTimeMs > 2000 || errorRate > 0.3f || taskCompletionRate < 0.5f -> CognitiveLoadLevel.OVERLOAD
            reactionTimeMs > 1000 || errorRate > 0.15f || taskCompletionRate < 0.7f -> CognitiveLoadLevel.HIGH
            reactionTimeMs > 500 || errorRate > 0.05f || taskCompletionRate < 0.85f -> CognitiveLoadLevel.MEDIUM
            else -> CognitiveLoadLevel.LOW
        }
        _cognitiveLoad.value = CognitiveLoadData(
            reactionTimeMs = reactionTimeMs,
            errorRate = errorRate,
            taskCompletionRate = taskCompletionRate,
            cognitiveLoadLevel = level
        )
        return level
    }

    // ==================== 医疗提醒（#65） ====================

    /**
     * 医疗提醒数据类
     */
    data class MedicalReminder(
        val id: String = "med_${System.currentTimeMillis()}",
        val type: String, // "checkup" / "medication" / "appointment" / "vaccination"
        val title: String,
        val description: String,
        val reminderTime: Long,
        val isCompleted: Boolean = false
    )

    /**
     * 添加医疗提醒
     */
    fun addMedicalReminder(reminder: MedicalReminder) {
        val current = _medicalReminders.value.toMutableList()
        current.add(reminder)
        _medicalReminders.value = current.sortedBy { it.reminderTime }
    }

    /**
     * 获取到期的医疗提醒
     */
    fun getDueMedicalReminders(): List<MedicalReminder> {
        val now = System.currentTimeMillis()
        return _medicalReminders.value.filter { it.reminderTime <= now && !it.isCompleted }
    }

    private val _medicalReminders = MutableStateFlow<List<MedicalReminder>>(emptyList())
    val medicalReminders: StateFlow<List<MedicalReminder>> = _medicalReminders.asStateFlow()

    // ==================== 用药管理（#66） ====================

    /**
     * 用药记录
     */
    data class MedicationRecord(
        val id: String,
        val medicationName: String,
        val dosage: String,
        val scheduledTime: Long,
        val takenTime: Long? = null,
        val isTaken: Boolean = false
    )

    private val _medicationRecords = MutableStateFlow<List<MedicationRecord>>(emptyList())
    val medicationRecords: StateFlow<List<MedicationRecord>> = _medicationRecords.asStateFlow()

    /**
     * 添加用药计划
     */
    fun addMedicationSchedule(medication: MedicationRecord) {
        val current = _medicationRecords.value.toMutableList()
        current.add(medication)
        _medicationRecords.value = current.sortedBy { it.scheduledTime }
    }

    /**
     * 记录用药
     */
    fun recordMedicationTaken(medicationId: String, takenTime: Long = System.currentTimeMillis()) {
        _medicationRecords.value = _medicationRecords.value.map { record ->
            if (record.id == medicationId) {
                record.copy(isTaken = true, takenTime = takenTime)
            } else record
        }
    }

    /**
     * 获取待服用的药物
     */
    fun getPendingMedications(): List<MedicationRecord> {
        val now = System.currentTimeMillis()
        return _medicationRecords.value.filter { !it.isTaken && it.scheduledTime <= now + 30 * 60 * 1000 }
    }

    // ==================== 健康趋势分析（#67） ====================

    /**
     * 健康趋势分析结果
     */
    data class HealthTrendAnalysis(
        val period: String,
        val heartRateTrend: TrendDirection,
        val bloodPressureTrend: TrendDirection,
        val temperatureTrend: TrendDirection,
        val overallAssessment: String,
        val recommendations: List<String>
    )

    enum class TrendDirection {
        IMPROVING, STABLE, WORSENING, INSUFFICIENT_DATA
    }

    /**
     * 分析健康趋势
     */
    fun analyzeHealthTrend(periodDays: Int = 7): HealthTrendAnalysis {
        val records = getHealthRecords(periodDays)

        val heartRateTrend = analyzeHeartRateTrend(records)
        val bpTrend = analyzeBloodPressureTrend(records)
        val tempTrend = analyzeTemperatureTrend(records)

        val recommendations = generateRecommendations(heartRateTrend, bpTrend, tempTrend)

        return HealthTrendAnalysis(
            period = "最近 $periodDays 天",
            heartRateTrend = heartRateTrend,
            bloodPressureTrend = bpTrend,
            temperatureTrend = tempTrend,
            overallAssessment = generateOverallAssessment(heartRateTrend, bpTrend, tempTrend),
            recommendations = recommendations
        )
    }

    private fun analyzeHeartRateTrend(records: List<HealthRecord>): TrendDirection {
        val heartRateRecords = records.filter { it.type == "heart_rate" }
        if (heartRateRecords.size < 3) return TrendDirection.INSUFFICIENT_DATA

        val recent = heartRateRecords.takeLast(3).map { it.value.toInt() }
        val avg = recent.average()

        return when {
            avg < 60 || avg > 100 -> TrendDirection.WORSENING
            recent.zipWithNext().all { (a, b) -> abs(a - b) < 10 } -> TrendDirection.STABLE
            else -> TrendDirection.INSUFFICIENT_DATA
        }
    }

    private fun analyzeBloodPressureTrend(records: List<HealthRecord>): TrendDirection {
        val bpRecords = records.filter { it.type == "blood_pressure" }
        if (bpRecords.size < 3) return TrendDirection.INSUFFICIENT_DATA

        val recent = bpRecords.takeLast(3).map { it.value }
        return if (recent.all { it == recent.first() }) {
            TrendDirection.STABLE
        } else {
            TrendDirection.INSUFFICIENT_DATA
        }
    }

    private fun analyzeTemperatureTrend(records: List<HealthRecord>): TrendDirection {
        val tempRecords = records.filter { it.type == "temperature" }
        if (tempRecords.size < 3) return TrendDirection.INSUFFICIENT_DATA

        val recent = tempRecords.takeLast(3).map { it.value.toFloat() }
        val avg = recent.average()

        return when {
            avg > 37.5 -> TrendDirection.WORSENING
            avg < 36.0 -> TrendDirection.WORSENING
            else -> TrendDirection.STABLE
        }
    }

    private fun generateRecommendations(
        heartRateTrend: TrendDirection,
        bpTrend: TrendDirection,
        tempTrend: TrendDirection
    ): List<String> {
        val recommendations = mutableListOf<String>()

        if (heartRateTrend == TrendDirection.WORSENING) {
            recommendations.add("心率异常，建议咨询医生")
        }
        if (bpTrend == TrendDirection.WORSENING) {
            recommendations.add("血压异常，建议监测并咨询医生")
        }
        if (tempTrend == TrendDirection.WORSENING) {
            recommendations.add("体温异常，建议休息并观察")
        }
        if (recommendations.isEmpty()) {
            recommendations.add("各项指标正常，请继续保持健康生活方式")
        }

        return recommendations
    }

    private fun generateOverallAssessment(
        heartRateTrend: TrendDirection,
        bpTrend: TrendDirection,
        tempTrend: TrendDirection
    ): String {
        val trends = listOf(heartRateTrend, bpTrend, tempTrend)
        val worseningCount = trends.count { it == TrendDirection.WORSENING }
        val stableCount = trends.count { it == TrendDirection.STABLE }

        return when {
            worseningCount >= 2 -> "健康状况需要关注，建议就医检查"
            stableCount >= 2 -> "健康状况良好，请继续保持"
            else -> "健康数据不足，建议持续监测"
        }
    }

    // ==================== 远程医疗会诊（#68） ====================

    /**
     * 远程医疗会诊会话
     */
    data class TelemedicineSession(
        val sessionId: String,
        val doctorId: String,
        val doctorName: String,
        val status: SessionStatus = SessionStatus.PENDING,
        val startTime: Long = 0,
        val endTime: Long = 0
    )

    enum class SessionStatus {
        PENDING, ACTIVE, COMPLETED, CANCELLED
    }

    private val _telemedicineSessions = MutableStateFlow<List<TelemedicineSession>>(emptyList())
    val telemedicineSessions: StateFlow<List<TelemedicineSession>> = _telemedicineSessions.asStateFlow()

    /**
     * 发起远程医疗会诊
     */
    fun initiateTelemedicineConsultation(doctorId: String, doctorName: String): TelemedicineSession {
        val session = TelemedicineSession(
            sessionId = "tele_${System.currentTimeMillis()}",
            doctorId = doctorId,
            doctorName = doctorName,
            status = SessionStatus.ACTIVE,
            startTime = System.currentTimeMillis()
        )
        _telemedicineSessions.value = _telemedicineSessions.value + session
        return session
    }

    /**
     * 结束会诊
     */
    fun endTelemedicineConsultation(sessionId: String) {
        _telemedicineSessions.value = _telemedicineSessions.value.map { session ->
            if (session.sessionId == sessionId) {
                session.copy(status = SessionStatus.COMPLETED, endTime = System.currentTimeMillis())
            } else session
        }
    }

    // ==================== 专家连线（#69） ====================

    /**
     * 专家连线：向配置的远程医疗平台发起真实连接请求。
     *
     * 平台地址通过 `jarvis_health` SharedPreferences 的 `telemedicine_endpoint`
     * 配置（例如 `https://telemed.example.com/api/connect`）。未配置或请求失败时
     * 返回 [ConnectionStatus.FAILED] 并附带原因。
     */
    suspend fun connectToExpert(specialty: String): ExpertConnection {
        val endpoint = context.getSharedPreferences("jarvis_health", Context.MODE_PRIVATE)
            .getString("telemedicine_endpoint", "")?.trim().orEmpty()
        if (endpoint.isEmpty()) {
            return ExpertConnection(
                connectionId = "",
                specialty = specialty,
                status = ConnectionStatus.FAILED,
                message = "未配置远程医疗平台地址，请在设置中填写 telemedicine_endpoint"
            )
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            try {
                val body = org.json.JSONObject().apply {
                    put("specialty", specialty)
                    put("patientId", android.provider.Settings.Secure.getString(
                        context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
                    ))
                    put("timestamp", System.currentTimeMillis())
                }.toString()
                connection = (java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                java.io.OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) {
                    val json = runCatching { org.json.JSONObject(text) }.getOrNull()
                    ExpertConnection(
                        connectionId = json?.optString("connectionId")
                            ?: "exp_${System.currentTimeMillis()}",
                        specialty = specialty,
                        status = ConnectionStatus.CONNECTED,
                        message = json?.optString("message") ?: "已连接${specialty}专家"
                    )
                } else {
                    Log.w(TAG, "专家连线失败 HTTP $code")
                    ExpertConnection("", specialty, ConnectionStatus.FAILED, "平台返回 HTTP $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "专家连线异常: ${e.message}")
                ExpertConnection("", specialty, ConnectionStatus.FAILED, "连接失败: ${e.message}")
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }

    data class ExpertConnection(
        val connectionId: String,
        val specialty: String,
        val status: ConnectionStatus,
        val message: String
    )

    enum class ConnectionStatus {
        CONNECTING, CONNECTED, DISCONNECTED, FAILED
    }

    // ==================== 病情数据共享（#70） ====================

    /**
     * 加密共享病情数据
     */
    suspend fun shareMedicalData(recipientId: String, data: String): Boolean {
        return try {
            val encryptedData = encryptMedicalData(data)
            val payload = "$recipientId\n${System.currentTimeMillis()}\n$encryptedData"
            val dir = File(context.filesDir, "medical_shares").apply { mkdirs() }
            val target = File(dir, "${recipientId}_${System.currentTimeMillis()}.enc")
            target.writeText(payload)
            Log.i(TAG, "加密病情数据已导出: ${target.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "shareMedicalData failed", e)
            false
        }
    }

    /** 读取某位接收者已导出的加密数据。 */
    fun readSharedMedicalData(recipientId: String): String? {
        val dir = File(context.filesDir, "medical_shares")
        val latest = dir.listFiles { file -> file.name.startsWith("${recipientId}_") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        return latest.readText()
    }

    private val medicalKeyPrefs by lazy {
        context.getSharedPreferences("jarvis_health", Context.MODE_PRIVATE)
    }

    private suspend fun encryptMedicalData(data: String): String {
        var key = medicalKeyPrefs.getString("medical_key", null)
        if (key.isNullOrBlank()) {
            key = cryptoManager.generateRandomKey()
            medicalKeyPrefs.edit().putString("medical_key", key).apply()
        }
        return cryptoManager.encryptWithKey(data, key)
    }

    // ==================== 生理状态挂钩（#71） ====================

    /**
     * 生理状态对 AI 应答的影响
     */
    fun getPhysiologicalStateImpact(): PhysiologicalImpact {
        val vitalSigns = _vitalSigns.value
        val fatigueData = _fatigueData.value
        val cognitiveLoad = _cognitiveLoad.value

        return PhysiologicalImpact(
            shouldAdjustResponseSpeed = vitalSigns.heartRate > 100 || vitalSigns.heartRate < 50,
            shouldReduceCognitiveLoad = cognitiveLoad.cognitiveLoadLevel == CognitiveLoadLevel.OVERLOAD,
            shouldSuggestRest = fatigueData.fatigueLevel == FatigueLevel.SEVERE,
            shouldShowConcern = vitalSigns.temperature > 37.5f || vitalSigns.bloodPressureSystolic > 140,
            responseTone = when {
                fatigueData.fatigueLevel == FatigueLevel.SEVERE -> ResponseTone.GENTLE
                cognitiveLoad.cognitiveLoadLevel == CognitiveLoadLevel.HIGH -> ResponseTone.SIMPLE
                else -> ResponseTone.NORMAL
            }
        )
    }

    data class PhysiologicalImpact(
        val shouldAdjustResponseSpeed: Boolean = false,
        val shouldReduceCognitiveLoad: Boolean = false,
        val shouldSuggestRest: Boolean = false,
        val shouldShowConcern: Boolean = false,
        val responseTone: ResponseTone = ResponseTone.NORMAL
    )

    enum class ResponseTone {
        NORMAL,     // 正常
        GENTLE,     // 温和
        SIMPLE      // 极简
    }

    // ==================== 健康记录管理 ====================

    /**
     * 健康记录
     */
    data class HealthRecord(
        val id: String = "hr_${System.currentTimeMillis()}",
        val type: String,
        val value: String,
        val unit: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _healthRecords = MutableStateFlow<List<HealthRecord>>(emptyList())
    val healthRecords: StateFlow<List<HealthRecord>> = _healthRecords.asStateFlow()

    /**
     * 添加健康记录
     */
    private fun addHealthRecord(record: HealthRecord) {
        val current = _healthRecords.value.toMutableList()
        current.add(record)
        _healthRecords.value = current.sortedByDescending { it.timestamp }
    }

    /**
     * 获取健康记录
     */
    fun getHealthRecords(days: Int = 7): List<HealthRecord> {
        val cutoff = System.currentTimeMillis() - days * 24 * 60 * 60 * 1000
        return _healthRecords.value.filter { it.timestamp >= cutoff }
    }

    /**
     * 获取最新健康记录
     */
    fun getLatestHealthRecord(type: String): HealthRecord? {
        return _healthRecords.value.firstOrNull { it.type == type }
    }

    // ==================== 传感器监听 ====================

    fun startMonitoring() {
        // 注册传感器监听
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stopMonitoring() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_HEART_RATE -> {
                _vitalSigns.value = _vitalSigns.value.copy(
                    heartRate = event.values[0].toInt()
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

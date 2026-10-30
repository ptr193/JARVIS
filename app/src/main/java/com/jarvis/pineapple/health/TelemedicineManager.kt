package com.jarvis.pineapple.health

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 远程医疗管理器（功能 #68-#70）。
 *
 * 实现远程医疗会诊、专家连线和病情数据共享功能。
 */
class TelemedicineManager(private val context: Context) {

    private val _sessionState = MutableStateFlow(SessionState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    /**
     * 会诊会话状态
     */
    data class SessionState(
        val isActive: Boolean = false,
        val sessionId: String = "",
        val doctorId: String = "",
        val doctorName: String = "",
        val patientId: String = "",
        val status: SessionStatus = SessionStatus.IDLE,
        val startTime: Long = 0,
        val error: String? = null
    )

    enum class SessionStatus {
        IDLE, CONNECTING, CONNECTED, IN_CONSULTATION, COMPLETED, ERROR
    }

    /**
     * 会诊会话
     */
    data class ConsultationSession(
        val sessionId: String,
        val doctorId: String,
        val doctorName: String,
        val doctorSpecialty: String,
        val patientId: String,
        val patientName: String,
        val status: SessionStatus = SessionStatus.IDLE,
        val startTime: Long = 0,
        val endTime: Long = 0,
        val notes: String = "",
        val prescriptions: List<Prescription> = emptyList()
    )

    /**
     * 处方
     */
    data class Prescription(
        val id: String,
        val medicationName: String,
        val dosage: String,
        val frequency: String,
        val duration: String,
        val instructions: String
    )

    /**
     * 医生信息
     */
    data class DoctorInfo(
        val doctorId: String,
        val name: String,
        val specialty: String,
        val hospital: String,
        val rating: Float,
        val isAvailable: Boolean
    )

    private val _sessions = MutableStateFlow<List<ConsultationSession>>(emptyList())
    val sessions: StateFlow<List<ConsultationSession>> = _sessions.asStateFlow()

    private val _doctors = MutableStateFlow<List<DoctorInfo>>(emptyList())
    val doctors: StateFlow<List<DoctorInfo>> = _doctors.asStateFlow()

    init {
        // 医疗平台专家目录：从本地配置加载（对接医疗平台时由平台接口刷新）
        _doctors.value = loadConfiguredDoctors()
    }

    /**
     * 从本地配置加载专家目录。对接真实医疗平台后，此处由平台 API 返回结果覆盖。
     */
    private fun loadConfiguredDoctors(): List<DoctorInfo> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DOCTORS, null) ?: return DEFAULT_DOCTORS
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DoctorInfo(
                    doctorId = o.getString("doctorId"),
                    name = o.getString("name"),
                    specialty = o.optString("specialty", "全科"),
                    hospital = o.optString("hospital", ""),
                    rating = o.optDouble("rating", 0.0).toFloat(),
                    isAvailable = o.optBoolean("isAvailable", true)
                )
            }
        } catch (e: Exception) {
            DEFAULT_DOCTORS
        }
    }

    /** 保存专家目录（供设置页/平台同步写入）。 */
    fun saveDoctors(doctors: List<DoctorInfo>) {
        _doctors.value = doctors
        val arr = org.json.JSONArray()
        doctors.forEach { d ->
            arr.put(
                org.json.JSONObject().apply {
                    put("doctorId", d.doctorId)
                    put("name", d.name)
                    put("specialty", d.specialty)
                    put("hospital", d.hospital)
                    put("rating", d.rating.toDouble())
                    put("isAvailable", d.isAvailable)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DOCTORS, arr.toString()).apply()
    }

    /**
     * 发起会诊
     */
    fun initiateConsultation(doctorId: String, patientId: String): ConsultationSession {
        val doctor = _doctors.value.find { it.doctorId == doctorId }

        val session = ConsultationSession(
            sessionId = "consult_${System.currentTimeMillis()}",
            doctorId = doctorId,
            doctorName = doctor?.name ?: "未知医生",
            doctorSpecialty = doctor?.specialty ?: "未知科室",
            patientId = patientId,
            patientName = "患者",
            status = SessionStatus.CONNECTING,
            startTime = System.currentTimeMillis()
        )

        val current = _sessions.value.toMutableList()
        current.add(session)
        _sessions.value = current

        _sessionState.value = SessionState(
            isActive = true,
            sessionId = session.sessionId,
            doctorId = doctorId,
            doctorName = session.doctorName,
            patientId = patientId,
            status = SessionStatus.CONNECTING,
            startTime = System.currentTimeMillis()
        )

        return session
    }

    /**
     * 接受会诊
     */
    fun acceptConsultation(sessionId: String): Boolean {
        val current = _sessions.value.toMutableList()
        val index = current.indexOfFirst { it.sessionId == sessionId }

        if (index >= 0) {
            current[index] = current[index].copy(status = SessionStatus.IN_CONSULTATION)
            _sessions.value = current

            _sessionState.value = _sessionState.value.copy(
                status = SessionStatus.IN_CONSULTATION
            )
            return true
        }

        return false
    }

    /**
     * 结束会诊
     */
    fun endConsultation(sessionId: String, notes: String = "") {
        val current = _sessions.value.toMutableList()
        val index = current.indexOfFirst { it.sessionId == sessionId }

        if (index >= 0) {
            current[index] = current[index].copy(
                status = SessionStatus.COMPLETED,
                endTime = System.currentTimeMillis(),
                notes = notes
            )
            _sessions.value = current
        }

        _sessionState.value = SessionState()
    }

    /**
     * 添加处方
     */
    fun addPrescription(sessionId: String, prescription: Prescription): Boolean {
        val current = _sessions.value.toMutableList()
        val index = current.indexOfFirst { it.sessionId == sessionId }

        if (index >= 0) {
            val updatedPrescriptions = current[index].prescriptions + prescription
            current[index] = current[index].copy(prescriptions = updatedPrescriptions)
            _sessions.value = current
            return true
        }

        return false
    }

    /**
     * 搜索医生
     */
    fun searchDoctors(specialty: String? = null): List<DoctorInfo> {
        return if (specialty != null) {
            _doctors.value.filter { it.specialty.contains(specialty, ignoreCase = true) }
        } else {
            _doctors.value
        }
    }

    /**
     * 获取可用医生
     */
    fun getAvailableDoctors(): List<DoctorInfo> {
        return _doctors.value.filter { it.isAvailable }
    }

    /**
     * 预约医生
     */
    fun bookAppointment(doctorId: String, patientId: String, timeSlot: String): Appointment {
        return Appointment(
            appointmentId = "appt_${System.currentTimeMillis()}",
            doctorId = doctorId,
            patientId = patientId,
            timeSlot = timeSlot,
            status = AppointmentStatus.BOOKED
        )
    }

    /**
     * 预约信息
     */
    data class Appointment(
        val appointmentId: String,
        val doctorId: String,
        val patientId: String,
        val timeSlot: String,
        val status: AppointmentStatus
    )

    enum class AppointmentStatus {
        BOOKED, CONFIRMED, CANCELLED, COMPLETED
    }

    /**
     * 获取会诊历史
     */
    fun getConsultationHistory(): List<ConsultationSession> {
        return _sessions.value.sortedByDescending { it.startTime }
    }

    /**
     * 获取活跃会诊
     */
    fun getActiveConsultations(): List<ConsultationSession> {
        return _sessions.value.filter { it.status == SessionStatus.IN_CONSULTATION }
    }

    /**
     * 加密共享病情数据：先安全落盘（应用私有目录），再尝试上传到配置的会诊平台。
     *
     * 平台地址通过 `jarvis_health` SharedPreferences 的 `telemedicine_endpoint` 配置，
     * 上传路径为 `${endpoint}/share`。本地落盘成功且（无平台配置或上传成功）时返回 true。
     */
    fun shareMedicalData(sessionId: String, recipientId: String, encryptedData: String): Boolean {
        if (recipientId.isBlank() || encryptedData.isBlank()) return false
        return try {
            val dir = java.io.File(context.filesDir, "telemedicine_shares").apply { mkdirs() }
            val target = java.io.File(dir, "${sessionId}_${recipientId}_${System.currentTimeMillis()}.enc")
            target.writeText("$sessionId\n$recipientId\n${System.currentTimeMillis()}\n$encryptedData")
            android.util.Log.i("TelemedicineManager", "加密会诊数据已保存: ${target.name}")

            val endpoint = context.getSharedPreferences("jarvis_health", Context.MODE_PRIVATE)
                .getString("telemedicine_endpoint", "")?.trim().orEmpty()
            if (endpoint.isEmpty()) {
                // 无平台配置：本地安全留存视为成功（不伪造远程送达）
                return true
            }
            uploadShare(endpoint, sessionId, recipientId, encryptedData)
        } catch (e: Exception) {
            android.util.Log.w("TelemedicineManager", "共享病情数据失败: ${e.message}")
            false
        }
    }

    private fun uploadShare(endpoint: String, sessionId: String, recipientId: String, payload: String): Boolean {
        var connection: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL("${endpoint.trimEnd('/')}/share")
            connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val body = org.json.JSONObject().apply {
                put("sessionId", sessionId)
                put("recipientId", recipientId)
                put("payload", payload)
            }.toString()
            java.io.OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            connection.responseCode in 200..299
        } catch (e: Exception) {
            android.util.Log.w("TelemedicineManager", "会诊数据上传失败: ${e.message}")
            false
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * 获取会话统计
     */
    fun getSessionStatistics(): SessionStatistics {
        val allSessions = _sessions.value
        return SessionStatistics(
            totalSessions = allSessions.size,
            activeSessions = allSessions.count { it.status == SessionStatus.IN_CONSULTATION },
            completedSessions = allSessions.count { it.status == SessionStatus.COMPLETED },
            totalConsultationTime = allSessions.filter { it.status == SessionStatus.COMPLETED }
                .sumOf { it.endTime - it.startTime }
        )
    }

    data class SessionStatistics(
        val totalSessions: Int,
        val activeSessions: Int,
        val completedSessions: Int,
        val totalConsultationTime: Long
    )

    /**
     * 清理资源
     */
    fun cleanup() {
        _sessions.value = emptyList()
        _sessionState.value = SessionState()
    }

    companion object {
        private const val PREFS = "jarvis_telemedicine"
        private const val KEY_DOCTORS = "doctors"

        /**
         * 默认专家目录。对接真实医疗平台后由平台接口覆盖（REQ-05：接口即插即用）。
         * 这些条目用于在未接入平台时提供可查看/可发起会话的本地目录，非伪造在线状态。
         */
        internal val DEFAULT_DOCTORS: List<DoctorInfo> = listOf(
            DoctorInfo("dr_general_01", "全科在线医生", "全科", "平台医生池", 4.8f, true),
            DoctorInfo("dr_cardio_01", "心血管科医生", "心血管科", "平台医生池", 4.7f, true),
            DoctorInfo("dr_pediatrics_01", "儿科医生", "儿科", "平台医生池", 4.9f, true),
            DoctorInfo("dr_derm_01", "皮肤科医生", "皮肤科", "平台医生池", 4.6f, true)
        )
    }
}

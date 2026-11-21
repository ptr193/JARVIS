package com.jarvis.pineapple.health

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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
        val raw = prefs.getString(KEY_DOCTORS, null) ?: return emptyList()
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
            emptyList()
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

    data class ConsultationStart(
        val success: Boolean,
        val message: String,
        val session: ConsultationSession? = null
    )

    /**
     * 发起会诊。未配置平台地址或无医生时失败关闭，不写入会话。
     */
    suspend fun initiateConsultation(doctorId: String, patientId: String): ConsultationStart {
        val doctor = _doctors.value.find { it.doctorId == doctorId }
        val endpoint = telemedicineEndpoint()
        val reason = consultationUnavailableReason(endpoint, doctor != null)
        if (reason != null) {
            _sessionState.value = SessionState(status = SessionStatus.ERROR, error = reason)
            return ConsultationStart(success = false, message = reason)
        }
        if (patientId.isBlank()) {
            val message = "患者标识不能为空"
            _sessionState.value = SessionState(status = SessionStatus.ERROR, error = message)
            return ConsultationStart(success = false, message = message)
        }

        val requestedAt = System.currentTimeMillis()
        val requestedSession = ConsultationSession(
            sessionId = "consult_$requestedAt",
            doctorId = doctorId,
            doctorName = doctor!!.name,
            doctorSpecialty = doctor.specialty,
            patientId = patientId,
            patientName = "患者",
            status = SessionStatus.CONNECTING,
            startTime = requestedAt
        )

        return withContext(Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = (java.net.URL("${endpoint.trimEnd('/')}/consultations").openConnection()
                    as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                val body = org.json.JSONObject().apply {
                    put("doctorId", doctorId)
                    put("patientId", patientId)
                    put("requestedAt", requestedAt)
                }.toString()
                java.io.OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val message = "会诊发起失败：平台返回 HTTP $code"
                    _sessionState.value = SessionState(status = SessionStatus.ERROR, error = message)
                    return@withContext ConsultationStart(success = false, message = message)
                }

                val json = runCatching { org.json.JSONObject(responseText) }.getOrNull()
                val session = requestedSession.copy(
                    sessionId = json?.optString("sessionId")?.ifBlank { requestedSession.sessionId }
                        ?: requestedSession.sessionId
                )
                _sessions.value = _sessions.value + session
                _sessionState.value = SessionState(
                    isActive = true,
                    sessionId = session.sessionId,
                    doctorId = doctorId,
                    doctorName = session.doctorName,
                    patientId = patientId,
                    status = SessionStatus.CONNECTING,
                    startTime = requestedAt
                )
                ConsultationStart(
                    success = true,
                    message = json?.optString("message")?.ifBlank { "会诊请求已发送至 ${session.doctorName}" }
                        ?: "会诊请求已发送至 ${session.doctorName}",
                    session = session
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = "会诊发起失败：${e.message ?: "网络异常"}"
                _sessionState.value = SessionState(status = SessionStatus.ERROR, error = message)
                ConsultationStart(success = false, message = message)
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }

    private fun telemedicineEndpoint(): String =
        context.getSharedPreferences("jarvis_health", Context.MODE_PRIVATE)
            .getString("telemedicine_endpoint", "")?.trim().orEmpty()

    /** 平台确认接受后，才将会话标记为会诊中。 */
    suspend fun acceptConsultation(sessionId: String): Boolean =
        updateRemoteSession(sessionId, "accept")

    /** 平台确认结束后，才完成本地会话记录。 */
    suspend fun endConsultation(sessionId: String, notes: String = ""): Boolean =
        updateRemoteSession(sessionId, "end", notes)

    private suspend fun updateRemoteSession(
        sessionId: String,
        action: String,
        notes: String = ""
    ): Boolean {
        val index = _sessions.value.indexOfFirst { it.sessionId == sessionId }
        val endpoint = telemedicineEndpoint()
        if (index < 0 || endpoint.isEmpty()) return false

        return withContext(Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            try {
                val encodedId = java.net.URLEncoder.encode(sessionId, Charsets.UTF_8.name())
                connection = (java.net.URL(
                    "${endpoint.trimEnd('/')}/consultations/$encodedId/$action"
                ).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val body = org.json.JSONObject().apply {
                    if (action == "end") put("notes", notes)
                }.toString()
                java.io.OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
                if (connection.responseCode !in 200..299) return@withContext false

                val current = _sessions.value.toMutableList()
                val currentIndex = current.indexOfFirst { it.sessionId == sessionId }
                if (currentIndex < 0) return@withContext false
                current[currentIndex] = if (action == "accept") {
                    current[currentIndex].copy(status = SessionStatus.IN_CONSULTATION)
                } else {
                    current[currentIndex].copy(
                        status = SessionStatus.COMPLETED,
                        endTime = System.currentTimeMillis(),
                        notes = notes
                    )
                }
                _sessions.value = current
                if (_sessionState.value.sessionId == sessionId) {
                    _sessionState.value = if (action == "accept") {
                        _sessionState.value.copy(status = SessionStatus.IN_CONSULTATION)
                    } else {
                        SessionState()
                    }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("TelemedicineManager", "会诊状态更新失败: ${e.message}")
                false
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
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
     * 向会诊平台上传加密病情数据，上传路径为 `${endpoint}/share`。
     * 只有平台返回 2xx 才表示共享成功；本地保存不等于远程送达。
     */
    suspend fun shareMedicalData(sessionId: String, recipientId: String, encryptedData: String): Boolean {
        val endpoint = telemedicineEndpoint()
        if (sessionId.isBlank() || recipientId.isBlank() || encryptedData.isBlank() || endpoint.isEmpty()) {
            return false
        }
        return withContext(Dispatchers.IO) {
            uploadShare(endpoint, sessionId, recipientId, encryptedData)
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
        const val NO_ENDPOINT = "未配置远程医疗平台地址，请在设置中填写 telemedicine_endpoint"
        const val NO_DOCTOR = "暂无在线医生"

        fun consultationUnavailableReason(endpoint: String?, doctorAvailable: Boolean): String? {
            if (endpoint.isNullOrBlank()) return NO_ENDPOINT
            if (!doctorAvailable) return NO_DOCTOR
            return null
        }
    }
}

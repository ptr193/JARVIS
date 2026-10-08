package com.jarvis.pineapple.health

/**
 * 生命体征监测：心率(PPG)、血压、体温
 */
class VitalSignsMonitor {

    data class VitalSigns(
        val heartRate: Int = 0,       // BPM
        val bloodPressure: Pair<Int, Int>? = null, // 收缩压/舒张压
        val temperature: Float = 0f,   // ℃
        val source: String = "camera_ppg"
    )

    /** 摄像头 PPG 心率监测（预留） */
    suspend fun measureHeartRate(): Int {
        // 预留：通过摄像头闪光灯 + PPG 算法
        return 72
    }

    /** 蓝牙穿戴设备数据接入（预留） */
    suspend fun syncFromWearable(): VitalSigns = VitalSigns()
}

/**
 * 疲劳监测：眨眼/打哈欠特征
 */
class FatigueMonitor {

    /** 检测到疲劳特征时提醒 */
    fun detectFatigue(eyeAspectRatio: Float, yawnCount: Int): Boolean {
        return eyeAspectRatio < 0.25f || yawnCount >= 3
    }
}

/**
 * 健康管理：用药提醒、健康趋势
 */
class HealthManager {

    data class Medication(val name: String, val dose: String, val timeMillis: Long)

    private val medications = mutableListOf<Medication>()

    fun addMedication(m: Medication) { medications.add(m) }

    fun getDueMedications(now: Long): List<Medication> =
        medications.filter { it.timeMillis <= now }

    /** 健康趋势分析 */
    fun analyzeTrend(history: List<VitalSignsMonitor.VitalSigns>): String {
        // 预留：统计分析
        return "心率正常范围"
    }
}

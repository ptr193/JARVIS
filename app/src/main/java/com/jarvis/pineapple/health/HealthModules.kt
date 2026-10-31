package com.jarvis.pineapple.health

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * 生命体征采集。
 *
 * 心率来源优先级：系统心率传感器 → 摄像头 PPG（[PpgMonitor]）。
 * 同时支持 BLE 心率带（Heart Rate Service 0x180D）。
 */
class VitalSignsMonitor(private val context: Context) {

    private val ppgMonitor = PpgMonitor(context)

    data class VitalSigns(
        val heartRate: Int = 0,
        val source: String = ""
    )

    /** 设备是否具备系统心率传感器。 */
    fun hasHeartRateSensor(): Boolean {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return manager.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null
    }

    /**
     * 测量心率：优先传感器，其次摄像头 PPG。
     *
     * @return 心率（BPM），失败时返回 0
     */
    suspend fun measureHeartRate(): Int {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (manager != null && sensor != null) {
            val reading = readHeartRateSensor(manager, sensor)
            if (reading > 0) return reading
        }
        return ppgMonitor.measure().bpm
    }

    /**
     * 从 BLE 心率带读取一次心率。
     *
     * @param deviceAddress 心率带蓝牙地址
     */
    suspend fun syncFromWearable(deviceAddress: String): VitalSigns {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) return VitalSigns(source = "bluetooth_unavailable")
        if (!hasBluetoothPermission()) return VitalSigns(source = "no_permission")

        val reading = withTimeoutOrNull(15_000L) { readHeartRateDevice(adapter, deviceAddress) } ?: 0
        return VitalSigns(heartRate = reading, source = "ble:$deviceAddress")
    }

    private suspend fun readHeartRateSensor(manager: SensorManager, sensor: Sensor): Int =
        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (event.values.isEmpty()) return
                        val bpm = event.values[0].toInt()
                        if (bpm in 20..250) {
                            manager.unregisterListener(this)
                            if (continuation.isActive) continuation.resume(bpm)
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                continuation.invokeOnCancellation { manager.unregisterListener(listener) }
            }
        } ?: 0

    private suspend fun readHeartRateDevice(
        adapter: android.bluetooth.BluetoothAdapter,
        address: String
    ): Int = suspendCancellableCoroutine { continuation ->
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            continuation.resume(0)
            return@suspendCancellableCoroutine
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (continuation.isActive) continuation.resume(0)
                        runCatching { gatt.close() }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val characteristic = gatt.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
                if (characteristic == null) {
                    if (continuation.isActive) continuation.resume(0)
                    runCatching { gatt.close() }
                    return
                }
                gatt.setCharacteristicNotification(characteristic, true)
                characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)?.let { descriptor ->
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                @Suppress("DEPRECATION")
                val body = characteristic.value ?: return
                deliver(gatt, parseHeartRate(body))
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                deliver(gatt, parseHeartRate(value))
            }

            private fun deliver(gatt: BluetoothGatt, bpm: Int) {
                if (bpm in 20..250 && continuation.isActive) {
                    continuation.resume(bpm)
                    runCatching { gatt.close() }
                }
            }
        }

        val gatt = device.connectGatt(context, false, callback)
        if (gatt == null && continuation.isActive) continuation.resume(0)
        continuation.invokeOnCancellation { runCatching { gatt?.close() } }
    }

    private fun parseHeartRate(body: ByteArray): Int {
        if (body.isEmpty()) return 0
        val flags = body[0].toInt()
        val isUInt16 = (flags and 0x01) != 0
        return if (isUInt16) {
            if (body.size < 3) 0
            else ((body[2].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        } else {
            body[1].toInt() and 0xFF
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private val HR_SERVICE: UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")
        private val HR_MEASUREMENT: UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")
        private val CLIENT_CHARACTERISTIC_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}

/**
 * 驾驶员疲劳监测：基于 PERCLOS（眼睑闭合时间比例）与打哈欠计数。
 */
class FatigueMonitor {

    private val eyeHistory = java.util.ArrayDeque<Float>()
    private val windowSize = 30

    fun detectFatigue(eyeAspectRatio: Float, yawnCount: Int): Boolean {
        eyeHistory.addLast(eyeAspectRatio)
        if (eyeHistory.size > windowSize) eyeHistory.removeFirst()
        return perclos() > PERCLOS_THRESHOLD || yawnCount >= 3
    }

    /** 眼睑闭合比例（0-1）。 */
    fun perclos(): Float {
        if (eyeHistory.isEmpty()) return 0f
        val closed = eyeHistory.count { it < 0.2f }
        return closed.toFloat() / eyeHistory.size
    }

    companion object {
        private const val PERCLOS_THRESHOLD = 0.3f
    }
}

/**
 * 用药提醒调度接口。生产路径接到 [com.jarvis.pineapple.system.CommunicationManager]。
 */
fun interface MedicationReminderScheduler {
    fun schedule(id: String, title: String, description: String, triggerTime: Long)
}

/**
 * 健康管理：用药提醒、健康趋势。
 */
class HealthManager(
    private val reminderScheduler: MedicationReminderScheduler? = null
) {

    data class Medication(val name: String, val dose: String, val timeMillis: Long)

    private val medications = mutableListOf<Medication>()

    fun addMedication(m: Medication) {
        medications.add(m)
        reminderScheduler?.schedule(
            id = "med_${m.name}_${m.timeMillis}",
            title = "用药提醒：${m.name}",
            description = "剂量 ${m.dose}",
            triggerTime = m.timeMillis
        )
    }

    fun getDueMedications(now: Long): List<Medication> =
        medications.filter { it.timeMillis <= now }

    fun analyzeTrend(history: List<VitalSignsMonitor.VitalSigns>): String {
        if (history.isEmpty()) return "无数据"
        val avgHr = history.map { it.heartRate }.filter { it > 0 }.average()
        if (avgHr.isNaN()) return "无有效数据"
        return when {
            avgHr > 100 -> "心率偏快"
            avgHr < 50 -> "心率偏慢"
            else -> "心率正常范围"
        }
    }
}

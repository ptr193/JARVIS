package com.jarvis.pineapple.perception

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/** 威胁预警通知渠道。 */
private const val THREAT_CHANNEL_ID = "jarvis_threats"

/** 威胁预警通知 ID。 */
private const val THREAT_NOTIFICATION_ID = 0x4A43

/**
 * 感知管理器（功能 #101-#116）。
 *
 * 功能：
 * - 环境感知与场景识别
 * - 手机姿态与方向感知
 * - 陀螺仪稳定辅助
 * - 监控画面调取
 * - 监控画面分析
 * - 多目标标记
 * - 多目标锁定
 * - 瞄准辅助
 * - 威胁探测
 * - 威胁预警
 * - 风险与骚扰信息识别
 * - 行为预测
 * - 路径预测
 */
class PerceptionManager(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // ==================== 环境感知与场景识别（#101） ====================

    /**
     * 场景识别结果
     */
    data class SceneRecognitionResult(
        val sceneType: SceneType = SceneType.UNKNOWN,
        val confidence: Float = 0f,
        val environmentData: EnvironmentData = EnvironmentData()
    )

    enum class SceneType {
        INDOOR, INDOOR_BRIGHT, OUTDOOR, OFFICE, HOME, BEDROOM, DESK, RESTAURANT, VEHICLE, HANDHELD, WALKING, UNKNOWN
    }

    data class EnvironmentData(
        val lightLevel: Float = 0f,
        val temperature: Float = 0f,
        val humidity: Float = 0f,
        val noiseLevel: Float = 0f,
        val airQuality: Int = 0
    )

    private val _sceneRecognition = MutableStateFlow(SceneRecognitionResult())
    val sceneRecognition: StateFlow<SceneRecognitionResult> = _sceneRecognition.asStateFlow()

    // 环境传感器原始读数（光照/温度/湿度/噪声/空气质量）
    private val _lightLevel = MutableStateFlow(0f)
    val lightLevel: StateFlow<Float> = _lightLevel.asStateFlow()
    private val _temperature = MutableStateFlow(0f)
    val temperature: StateFlow<Float> = _temperature.asStateFlow()
    private val _humidity = MutableStateFlow(0f)
    val humidity: StateFlow<Float> = _humidity.asStateFlow()
    private val _noiseLevel = MutableStateFlow(0f)
    val noiseLevel: StateFlow<Float> = _noiseLevel.asStateFlow()
    private val _airQuality = MutableStateFlow(0)
    val airQuality: StateFlow<Int> = _airQuality.asStateFlow()

    /** 外部注入的截屏管理器（用于监控画面截取）。 */
    var screenshotManager: com.jarvis.pineapple.system.ScreenshotManager? = null

    /** 网络摄像头快照地址（RTSP/ONVIF 或 HTTP 快照）。 */
    var networkCameraSnapshotUrl: String? = null

    /**
     * 识别当前场景
     */
    fun recognizeScene(): SceneRecognitionResult {
        // 基于传感器数据的真实场景识别
        val orientation = _orientation.value
        val lightLevel = _lightLevel.value
        val gyroData = _gyroscopeData.value

        val sceneType = when {
            abs(orientation.pitch) < 15f && abs(orientation.roll) < 15f -> {
                // 手机平放：可能是桌面/床上
                if (lightLevel < 50f) SceneType.BEDROOM
                else if (lightLevel > 500f) SceneType.OUTDOOR
                else SceneType.DESK
            }
            abs(orientation.pitch) > 75f || abs(orientation.roll) > 75f -> {
                // 手机竖屏：可能是手持/通话
                if (gyroData.totalVelocity > 1.0f) SceneType.WALKING
                else SceneType.HANDHELD
            }
            else -> {
                // 手机倾斜：可能是使用中
                if (lightLevel > 300f) SceneType.INDOOR_BRIGHT
                else SceneType.INDOOR
            }
        }

        val confidence = when (sceneType) {
            SceneType.OUTDOOR -> 0.9f
            SceneType.BEDROOM -> 0.85f
            SceneType.DESK -> 0.8f
            SceneType.HANDHELD -> 0.75f
            SceneType.WALKING -> 0.7f
            else -> 0.6f
        }

        val result = SceneRecognitionResult(
            sceneType = sceneType,
            confidence = confidence,
            environmentData = EnvironmentData(
                lightLevel = lightLevel,
                temperature = _temperature.value,
                humidity = _humidity.value,
                noiseLevel = _noiseLevel.value,
                airQuality = _airQuality.value
            )
        )
        _sceneRecognition.value = result
        return result
    }

    // ==================== 手机姿态与方向感知（#102） ====================

    /**
     * 手机姿态数据
     */
    data class PhoneOrientation(
        val pitch: Float = 0f,      // 俯仰角
        val roll: Float = 0f,       // 翻滚角
        val azimuth: Float = 0f,    // 方位角
        val isFlat: Boolean = false, // 是否平放
        val isUpright: Boolean = false // 是否竖直
    )

    private val _orientation = MutableStateFlow(PhoneOrientation())
    val orientation: StateFlow<PhoneOrientation> = _orientation.asStateFlow()

    /**
     * 获取手机姿态
     */
    fun getPhoneOrientation(): PhoneOrientation {
        return _orientation.value
    }

    /**
     * 检测手机是否平放
     */
    fun isPhoneFlat(): Boolean {
        val orient = _orientation.value
        return abs(orient.pitch) < 15f && abs(orient.roll) < 15f
    }

    /**
     * 检测手机是否竖直
     */
    fun isPhoneUpright(): Boolean {
        val orient = _orientation.value
        return abs(orient.pitch) > 75f || abs(orient.roll) > 75f
    }

    // ==================== 陀螺仪稳定辅助（#103） ====================

    /**
     * 陀螺仪数据
     */
    data class GyroscopeData(
        val angularVelocityX: Float = 0f,
        val angularVelocityY: Float = 0f,
        val angularVelocityZ: Float = 0f,
        val isStable: Boolean = true,
        val stabilityScore: Float = 1f
    ) {
        /** 三轴角速度合成值。 */
        val totalVelocity: Float
            get() = sqrt(
                angularVelocityX * angularVelocityX +
                    angularVelocityY * angularVelocityY +
                    angularVelocityZ * angularVelocityZ
            )
    }

    private val _gyroscopeData = MutableStateFlow(GyroscopeData())
    val gyroscopeData: StateFlow<GyroscopeData> = _gyroscopeData.asStateFlow()

    /**
     * 获取陀螺仪稳定状态
     */
    fun getStabilityStatus(): GyroscopeData {
        return _gyroscopeData.value
    }

    /**
     * 判断设备是否稳定
     */
    fun isDeviceStable(): Boolean {
        val gyro = _gyroscopeData.value
        val totalVelocity = sqrt(
            gyro.angularVelocityX * gyro.angularVelocityX +
            gyro.angularVelocityY * gyro.angularVelocityY +
            gyro.angularVelocityZ * gyro.angularVelocityZ
        )
        return totalVelocity < 0.5f
    }

    // ==================== 监控画面调取（#104） ====================

    /**
     * 监控画面
     */
    data class SurveillanceFrame(
        val frameId: String,
        val timestamp: Long,
        val source: SurveillanceSource,
        val imageData: ByteArray? = null,
        val isAnalyzed: Boolean = false
    )

    enum class SurveillanceSource {
        CAMERA_FRONT, CAMERA_BACK, NETWORK_CAMERA, SCREENSHOT, LOCAL_CAMERA, DOORBELL, OTHER
    }

    /**
     * 调取监控画面
     */
    fun captureSurveillanceFrame(source: SurveillanceSource): SurveillanceFrame {
        // 基于摄像头 / 网络摄像头 / 屏幕截取的实时帧捕获
        val frameData = when (source) {
            SurveillanceSource.CAMERA_FRONT -> captureCameraFrame(front = true)
            SurveillanceSource.CAMERA_BACK, SurveillanceSource.LOCAL_CAMERA -> captureCameraFrame(front = false)
            SurveillanceSource.SCREENSHOT -> captureScreenshot()
            SurveillanceSource.NETWORK_CAMERA,
            SurveillanceSource.DOORBELL,
            SurveillanceSource.OTHER -> captureNetworkCameraFrame()
        }

        return SurveillanceFrame(
            frameId = "frame_${System.currentTimeMillis()}",
            timestamp = System.currentTimeMillis(),
            source = source,
            imageData = frameData,
            isAnalyzed = false
        )
    }

    /**
     * 使用 Camera2 API 同步捕获一帧 JPEG。
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun captureCameraFrame(front: Boolean): ByteArray? {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (ObjectDetector.ensureCameraPermission(granted) != null) {
            return null
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE)
            as? android.hardware.camera2.CameraManager ?: return null

        val cameraId = try {
            manager.cameraIdList.firstOrNull { id ->
                val facing = manager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                if (front) {
                    facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                }
            } ?: manager.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return null

        val thread = android.os.HandlerThread("jarvis-camera").apply { start() }
        val handler = android.os.Handler(thread.looper)
        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
        var device: android.hardware.camera2.CameraDevice? = null
        var reader: android.media.ImageReader? = null
        try {
            val captureSize = android.util.Size(1280, 720)
            reader = android.media.ImageReader.newInstance(
                captureSize.width, captureSize.height, android.graphics.ImageFormat.JPEG, 2
            )
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage()
                if (image != null) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        holder.set(bytes)
                    } finally {
                        image.close()
                        latch.countDown()
                    }
                }
            }, handler)

            val callback = object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                    device = camera
                    try {
                        val sessionLatch = java.util.concurrent.CountDownLatch(1)
                        camera.createCaptureSession(
                            listOf(reader!!.surface),
                            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                    try {
                                        val request = session.device
                                            .createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE)
                                            .apply {
                                                addTarget(reader!!.surface)
                                                set(
                                                    android.hardware.camera2.CaptureRequest.CONTROL_MODE,
                                                    android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO
                                                )
                                            }
                                        session.capture(request.build(), null, handler)
                                    } catch (e: Exception) {
                                        // 忽略，由 latch 超时兜底
                                    } finally {
                                        sessionLatch.countDown()
                                    }
                                }

                                override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                    sessionLatch.countDown()
                                }
                            },
                            handler
                        )
                        sessionLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        latch.countDown()
                    }
                }

                override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                    camera.close()
                    latch.countDown()
                }

                override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                    camera.close()
                    latch.countDown()
                }
            }
            manager.openCamera(cameraId, callback, handler)
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            return null
        } finally {
            try { reader?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            thread.quitSafely()
        }
        return holder.get()
    }

    private fun captureNetworkCameraFrame(): ByteArray? {
        val url = networkCameraSnapshotUrl ?: return null
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun captureScreenshot(): ByteArray? {
        val manager = screenshotManager ?: return null
        if (!manager.hasPermission()) return null
        val file = manager.captureToFile() ?: return null
        return try {
            file.readBytes()
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 监控画面分析（#105） ====================

    /**
     * 画面分析结果
     */
    data class FrameAnalysisResult(
        val frameId: String,
        val detectedObjects: List<DetectedObject>,
        val detectedFaces: List<DetectedFace>,
        val anomalies: List<Anomaly>,
        val analysisTimeMs: Long
    )

    data class DetectedObject(
        val label: String,
        val confidence: Float,
        val boundingBox: BoundingBox
    )

    data class DetectedFace(
        val faceId: String,
        val confidence: Float,
        val boundingBox: BoundingBox,
        val isRecognized: Boolean = false,
        val identity: String? = null
    )

    data class BoundingBox(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    data class Anomaly(
        val type: AnomalyType,
        val description: String,
        val severity: AnomalySeverity,
        val timestamp: Long
    )

    enum class AnomalyType {
        MOTION, SOUND, OBJECT, FACE, SUDDEN_MOVEMENT, DEVICE_FALLEN, OTHER
    }

    enum class AnomalySeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    /**
     * 分析监控画面
     */
    /** 已被识别（登记）的人脸 ID 集合，用于区分熟人与陌生人。 */
    private val recognizedFaceIds: MutableSet<Int> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 标记某张人脸为已识别（由 FaceRecognitionManager 在匹配成功后调用）。 */
    fun markFaceRecognized(faceId: Int) {
        recognizedFaceIds.add(faceId)
    }

    suspend fun analyzeFrame(frame: SurveillanceFrame): FrameAnalysisResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            val detectedObjects = mutableListOf<DetectedObject>()
            val detectedFaces = mutableListOf<DetectedFace>()
            val anomalies = mutableListOf<Anomaly>()

            val bitmap = frame.imageData?.let { bytes ->
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            if (bitmap != null) {
                try {
                    val objectDetector = MLKitObjectDetector(context)
                    objectDetector.detect(bitmap).objects.forEach { obj ->
                        detectedObjects.add(
                            DetectedObject(
                                label = obj.label,
                                confidence = obj.confidence,
                                boundingBox = BoundingBox(
                                    obj.boundingBox.left, obj.boundingBox.top,
                                    obj.boundingBox.right, obj.boundingBox.bottom
                                )
                            )
                        )
                    }

                    val faceDetector = MLKitFaceDetector(context)
                    faceDetector.detect(bitmap).faces.forEach { face ->
                        detectedFaces.add(
                            DetectedFace(
                                faceId = face.faceId.toString(),
                                confidence = face.confidence,
                                boundingBox = BoundingBox(
                                    face.boundingBox.left, face.boundingBox.top,
                                    face.boundingBox.right, face.boundingBox.bottom
                                ),
                                isRecognized = recognizedFaceIds.contains(face.faceId)
                            )
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.w("PerceptionManager", "画面分析失败", e)
                } finally {
                    bitmap.recycle()
                }
            }

            updateTrackedObjects(
                detectedObjects.mapIndexed { index, obj ->
                    TrackedObject(
                        id = "obj_${startTime}_$index",
                        label = obj.label,
                        boundingBox = obj.boundingBox,
                        confidence = obj.confidence
                    )
                }
            )

            val orientation = _orientation.value
            val gyroData = _gyroscopeData.value

            if (gyroData.totalVelocity > 5.0f) {
                anomalies.add(
                    Anomaly(
                        type = AnomalyType.SUDDEN_MOVEMENT,
                        severity = AnomalySeverity.HIGH,
                        description = "检测到突然的移动",
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            if (abs(orientation.pitch) > 80f) {
                anomalies.add(
                    Anomaly(
                        type = AnomalyType.DEVICE_FALLEN,
                        severity = AnomalySeverity.MEDIUM,
                        description = "设备可能已掉落",
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            FrameAnalysisResult(
                frameId = frame.frameId,
                detectedObjects = detectedObjects,
                detectedFaces = detectedFaces,
                anomalies = anomalies,
                analysisTimeMs = System.currentTimeMillis() - startTime
            )
        }

    // ==================== 多目标标记（#106） ====================

    /**
     * 标记的目标
     */
    data class TrackedObject(
        val id: String,
        val label: String,
        val boundingBox: BoundingBox,
        val confidence: Float,
        val isMarked: Boolean = false,
        val markColor: Int = 0xFFFF0000.toInt()
    )

    private val _markedObjects = MutableStateFlow<List<TrackedObject>>(emptyList())
    val markedObjects: StateFlow<List<TrackedObject>> = _markedObjects.asStateFlow()

    private val _trackedObjects = MutableStateFlow<List<TrackedObject>>(emptyList())
    val trackedObjects: StateFlow<List<TrackedObject>> = _trackedObjects.asStateFlow()

    /**
     * 用最新一帧的检测结果刷新可标记目标集合。
     * 每次 [analyzeFrame] 后调用，使标记/锁定作用于真实检测框。
     */
    fun updateTrackedObjects(objects: List<TrackedObject>) {
        _trackedObjects.value = objects
        val ids = objects.map { it.id }.toSet()
        // 保留仍在画面中的标记及其颜色，移除已消失的目标
        val previous = _markedObjects.value.associateBy { it.id }
        _markedObjects.value = objects.map { obj ->
            val old = previous[obj.id]
            if (old != null && old.isMarked) {
                obj.copy(isMarked = true, markColor = old.markColor)
            } else {
                obj
            }
        }
        // 同步移除已消失目标的锁定
        _lockedObjects.value = _lockedObjects.value.filter { it.id in ids }
    }

    /**
     * 标记目标（需目标存在于当前跟踪集合中）
     */
    fun markObject(objectId: String, color: Int = 0xFFFF0000.toInt()): Boolean {
        val current = _markedObjects.value.toMutableList()
        val index = current.indexOfFirst { it.id == objectId }
        if (index >= 0) {
            current[index] = current[index].copy(isMarked = true, markColor = color)
            _markedObjects.value = current
            return true
        }
        val tracked = _trackedObjects.value.firstOrNull { it.id == objectId } ?: return false
        _markedObjects.value = current + tracked.copy(isMarked = true, markColor = color)
        return true
    }

    /**
     * 取消标记
     */
    fun unmarkObject(objectId: String): Boolean {
        val current = _markedObjects.value.toMutableList()
        val index = current.indexOfFirst { it.id == objectId }
        if (index >= 0) {
            current[index] = current[index].copy(isMarked = false)
            _markedObjects.value = current
            return true
        }
        return false
    }

    // ==================== 多目标锁定（#107） ====================

    /**
     * 锁定的目标
     */
    data class LockedObject(
        val id: String,
        val label: String,
        val boundingBox: BoundingBox,
        val lockType: LockType,
        val isTracking: Boolean = true
    )

    enum class LockType {
        POSITION, FACE, MOTION, FULL
    }

    private val _lockedObjects = MutableStateFlow<List<LockedObject>>(emptyList())
    val lockedObjects: StateFlow<List<LockedObject>> = _lockedObjects.asStateFlow()

    /**
     * 锁定目标
     */
    fun lockObject(objectId: String, lockType: LockType = LockType.FULL): Boolean {
        if (_lockedObjects.value.any { it.id == objectId }) return false
        val tracked = _trackedObjects.value.firstOrNull { it.id == objectId }
            ?: _markedObjects.value.firstOrNull { it.id == objectId }
            ?: return false
        _lockedObjects.value = _lockedObjects.value + LockedObject(
            id = objectId,
            label = tracked.label,
            boundingBox = tracked.boundingBox,
            lockType = lockType
        )
        return true
    }

    /**
     * 解锁目标
     */
    fun unlockObject(objectId: String): Boolean {
        val current = _lockedObjects.value.toMutableList()
        val removed = current.removeAll { it.id == objectId }
        if (removed) {
            _lockedObjects.value = current
            return true
        }
        return false
    }

    // ==================== 瞄准辅助（#108） ====================

    /**
     * 瞄准辅助数据
     */
    data class AimingAssistData(
        val targetX: Float,
        val targetY: Float,
        val assistLines: List<AssistLine>,
        val isLocked: Boolean = false
    )

    data class AssistLine(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val color: Int
    )

    /**
     * 获取瞄准辅助
     */
    fun getAimingAssist(targetX: Float, targetY: Float): AimingAssistData {
        val assistLines = listOf(
            AssistLine(targetX - 50, targetY, targetX + 50, targetY, 0xFFFF0000.toInt()),
            AssistLine(targetX, targetY - 50, targetX, targetY + 50, 0xFFFF0000.toInt())
        )
        return AimingAssistData(
            targetX = targetX,
            targetY = targetY,
            assistLines = assistLines,
            isLocked = true
        )
    }

    // ==================== 威胁探测（#109） ====================

    /**
     * 威胁探测结果
     */
    data class ThreatDetectionResult(
        val threatLevel: ThreatLevel = ThreatLevel.NONE,
        val threats: List<Threat> = emptyList(),
        val detectionTime: Long = System.currentTimeMillis()
    )

    enum class ThreatLevel {
        NONE, LOW, MEDIUM, HIGH, CRITICAL
    }

    data class Threat(
        val type: ThreatType,
        val description: String,
        val confidence: Float,
        val severity: ThreatLevel,
        val location: String? = null
    )

    enum class ThreatType {
        STRANGER, DANGEROUS_OBJECT, SUSPICIOUS_BEHAVIOR, FIRE, SMOKE, INTRUDER
    }

    /**
     * 探测威胁
     */
    fun detectThreats(analysisResult: FrameAnalysisResult): ThreatDetectionResult {
        val threats = mutableListOf<Threat>()

        // 检测陌生人
        val unknownFaces = analysisResult.detectedFaces.filter { !it.isRecognized }
        if (unknownFaces.isNotEmpty()) {
            threats.add(Threat(
                type = ThreatType.STRANGER,
                description = "检测到 ${unknownFaces.size} 个陌生人",
                confidence = 0.8f,
                severity = ThreatLevel.MEDIUM
            ))
        }

        // 检测异常
        analysisResult.anomalies.forEach { anomaly ->
            threats.add(Threat(
                type = when (anomaly.type) {
                    AnomalyType.MOTION -> ThreatType.SUSPICIOUS_BEHAVIOR
                    AnomalyType.OBJECT -> ThreatType.DANGEROUS_OBJECT
                    else -> ThreatType.SUSPICIOUS_BEHAVIOR
                },
                description = anomaly.description,
                confidence = 0.7f,
                severity = when (anomaly.severity) {
                    AnomalySeverity.LOW -> ThreatLevel.LOW
                    AnomalySeverity.MEDIUM -> ThreatLevel.MEDIUM
                    AnomalySeverity.HIGH -> ThreatLevel.HIGH
                    AnomalySeverity.CRITICAL -> ThreatLevel.CRITICAL
                }
            ))
        }

        val overallThreatLevel = if (threats.isEmpty()) {
            ThreatLevel.NONE
        } else {
            threats.maxOf { it.severity }
        }

        return ThreatDetectionResult(
            threatLevel = overallThreatLevel,
            threats = threats
        )
    }

    // ==================== 威胁预警（#110） ====================

    /**
     * 发送威胁预警
     */
    fun sendThreatAlert(threatResult: ThreatDetectionResult): ThreatAlert {
        var notified = false
        if (threatResult.threats.isNotEmpty()) {
            notified = runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    manager.createNotificationChannel(
                        android.app.NotificationChannel(
                            THREAT_CHANNEL_ID,
                            "威胁预警",
                            android.app.NotificationManager.IMPORTANCE_HIGH
                        )
                    )
                }
                val summary = threatResult.threats.take(3).joinToString("；") { it.description }
                val notification = androidx.core.app.NotificationCompat
                    .Builder(context, THREAT_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("JARVIS 威胁预警（${threatResult.threatLevel}）")
                    .setContentText(summary)
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(summary))
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
                manager.notify(THREAT_NOTIFICATION_ID, notification)
                true
            }.getOrDefault(false)
        }
        return ThreatAlert(
            alertId = "alert_${System.currentTimeMillis()}",
            threatLevel = threatResult.threatLevel,
            threats = threatResult.threats,
            timestamp = System.currentTimeMillis(),
            isNotified = notified
        )
    }

    data class ThreatAlert(
        val alertId: String,
        val threatLevel: ThreatLevel,
        val threats: List<Threat>,
        val timestamp: Long,
        val isNotified: Boolean
    )

    // ==================== 风险与骚扰信息识别（#111） ====================

    /**
     * 风险信息识别结果
     */
    data class RiskMessageResult(
        val isRisky: Boolean = false,
        val riskType: RiskType = RiskType.NONE,
        val riskLevel: RiskLevel = RiskLevel.LOW,
        val reason: String = ""
    )

    enum class RiskType {
        NONE, SPAM, PHISHING, FRAUD, HARASSMENT, THREAT
    }

    enum class RiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    /**
     * 识别风险信息
     */
    fun identifyRiskMessage(message: String): RiskMessageResult {
        val lowerMessage = message.lowercase()

        // 检测垃圾信息
        if (lowerMessage.containsAny("中奖", "免费", "点击链接", "限时", "urgent", "free", "click here")) {
            return RiskMessageResult(
                isRisky = true,
                riskType = RiskType.SPAM,
                riskLevel = RiskLevel.MEDIUM,
                reason = "检测到可能的垃圾信息特征"
            )
        }

        // 检测钓鱼信息
        if (lowerMessage.containsAny("验证账户", "密码过期", "银行", "verify", "password expired")) {
            return RiskMessageResult(
                isRisky = true,
                riskType = RiskType.PHISHING,
                riskLevel = RiskLevel.HIGH,
                reason = "检测到可能的钓鱼信息特征"
            )
        }

        // 检测诈骗信息
        if (lowerMessage.containsAny("转账", "汇款", "汇款", "transfer money", "wire transfer")) {
            return RiskMessageResult(
                isRisky = true,
                riskType = RiskType.FRAUD,
                riskLevel = RiskLevel.HIGH,
                reason = "检测到可能的诈骗信息特征"
            )
        }

        return RiskMessageResult(
            isRisky = false,
            riskType = RiskType.NONE,
            riskLevel = RiskLevel.LOW,
            reason = "未检测到风险特征"
        )
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it, ignoreCase = true) }
    }

    // ==================== 行为预测（#112） ====================

    /**
     * 行为预测结果
     */
    data class BehaviorPredictionResult(
        val predictedAction: String = "",
        val confidence: Float = 0f,
        val alternativeActions: List<PredictedAction> = emptyList(),
        val predictionTime: Long = System.currentTimeMillis()
    )

    data class PredictedAction(
        val action: String,
        val probability: Float
    )

    /**
     * 预测用户行为
     */
    /**
     * 预测用户行为：构建一阶马尔可夫转移模型，由当前行为推断下一个行为及其概率。
     */
    fun predictBehavior(behaviorHistory: List<UserBehavior>): BehaviorPredictionResult {
        if (behaviorHistory.isEmpty()) {
            return BehaviorPredictionResult(predictedAction = "unknown", confidence = 0f)
        }

        val sorted = behaviorHistory.sortedBy { it.timestamp }
        val transitions = mutableMapOf<String, MutableMap<String, Int>>()
        for (i in 0 until sorted.size - 1) {
            val from = sorted[i].action
            val to = sorted[i + 1].action
            transitions.getOrPut(from) { mutableMapOf() }.merge(to, 1, Int::plus)
        }

        val current = sorted.last().action
        val distribution = transitions[current]
        if (distribution.isNullOrEmpty()) {
            return BehaviorPredictionResult(
                predictedAction = current,
                confidence = 0.2f,
                alternativeActions = emptyList()
            )
        }

        val total = distribution.values.sum().toFloat()
        val ranked = distribution.entries
            .map { PredictedAction(it.key, (it.value / total).coerceIn(0f, 1f)) }
            .sortedByDescending { it.probability }

        return BehaviorPredictionResult(
            predictedAction = ranked.first().action,
            confidence = ranked.first().probability,
            alternativeActions = ranked.drop(1).take(4)
        )
    }

    data class UserBehavior(
        val action: String,
        val timestamp: Long,
        val context: Map<String, String> = emptyMap()
    )

    // ==================== 路径预测（#113） ====================

    /**
     * 路径预测结果
     */
    data class PathPredictionResult(
        val predictedPath: List<LocationPoint> = emptyList(),
        val confidence: Float = 0f,
        val estimatedTimeMinutes: Int = 0,
        val predictionTime: Long = System.currentTimeMillis()
    )

    data class LocationPoint(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long
    )

    /**
     * 预测移动路径
     */
    /**
     * 预测移动路径：对最近的位置序列做最小二乘线性趋势拟合（匀速模型），
     * 外推出未来若干时间点的位置，置信度取拟合的决定系数 R²。
     */
    fun predictPath(locationHistory: List<LocationPoint>): PathPredictionResult {
        val recent = locationHistory.sortedBy { it.timestamp }.takeLast(8)
        if (recent.size < 2) {
            return PathPredictionResult(predictedPath = emptyList(), confidence = 0f, estimatedTimeMinutes = 0)
        }

        val t0 = recent.first().timestamp
        val times = recent.map { (it.timestamp - t0).toDouble() }
        val meanT = times.average()
        val meanLat = recent.map { it.latitude }.average()
        val meanLng = recent.map { it.longitude }.average()

        var varT = 0.0
        var covLat = 0.0
        var covLng = 0.0
        for (i in recent.indices) {
            val dt = times[i] - meanT
            varT += dt * dt
            covLat += dt * (recent[i].latitude - meanLat)
            covLng += dt * (recent[i].longitude - meanLng)
        }
        if (varT < 1e-9) {
            return PathPredictionResult(emptyList(), 0f, 0)
        }

        val slopeLat = covLat / varT      // 每毫秒
        val slopeLng = covLng / varT

        var ssTotal = 0.0
        var ssResidual = 0.0
        for (i in recent.indices) {
            val dt = times[i]
            ssTotal += (recent[i].latitude - meanLat) * (recent[i].latitude - meanLat) +
                (recent[i].longitude - meanLng) * (recent[i].longitude - meanLng)
            val predictedLat = meanLat + slopeLat * dt
            val predictedLng = meanLng + slopeLng * dt
            ssResidual += (recent[i].latitude - predictedLat) * (recent[i].latitude - predictedLat) +
                (recent[i].longitude - predictedLng) * (recent[i].longitude - predictedLng)
        }
        val r2 = if (ssTotal > 1e-12) (1.0 - ssResidual / ssTotal).coerceIn(0.0, 1.0) else 0.0

        val last = recent.last()
        val stepMinutes = 5
        val points = (1..3).map { step ->
            val timestamp = last.timestamp + step.toLong() * stepMinutes * 60_000L
            val deltaMs = (timestamp - last.timestamp).toDouble()
            LocationPoint(
                latitude = last.latitude + slopeLat * deltaMs,
                longitude = last.longitude + slopeLng * deltaMs,
                timestamp = timestamp
            )
        }

        return PathPredictionResult(
            predictedPath = points,
            confidence = r2.toFloat(),
            estimatedTimeMinutes = stepMinutes * points.size
        )
    }

    // ==================== 传感器监听 ====================

    fun startMonitoring() {
        // 注册传感器监听
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stopMonitoring() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                // 计算姿态角
                val pitch = Math.atan2(y.toDouble(), sqrt((x * x + z * z).toDouble())) * 180 / Math.PI
                val roll = Math.atan2(x.toDouble(), sqrt((y * y + z * z).toDouble())) * 180 / Math.PI

                _orientation.value = _orientation.value.copy(
                    pitch = pitch.toFloat(),
                    roll = roll.toFloat(),
                    isFlat = abs(pitch) < 15f && abs(roll) < 15f,
                    isUpright = abs(pitch) > 75f || abs(roll) > 75f
                )
            }
            Sensor.TYPE_GYROSCOPE -> {
                val gyroX = event.values[0]
                val gyroY = event.values[1]
                val gyroZ = event.values[2]

                val totalVelocity = sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ)

                _gyroscopeData.value = GyroscopeData(
                    angularVelocityX = gyroX,
                    angularVelocityY = gyroY,
                    angularVelocityZ = gyroZ,
                    isStable = totalVelocity < 0.5f,
                    stabilityScore = (1f - totalVelocity).coerceIn(0f, 1f)
                )
            }
            Sensor.TYPE_LIGHT -> {
                val lightLevel = event.values[0]
                _lightLevel.value = lightLevel
                _sceneRecognition.value = _sceneRecognition.value.copy(
                    environmentData = _sceneRecognition.value.environmentData.copy(
                        lightLevel = lightLevel
                    )
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

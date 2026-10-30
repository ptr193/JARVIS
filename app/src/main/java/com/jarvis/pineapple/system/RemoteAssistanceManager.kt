package com.jarvis.pineapple.system

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 远程协助管理器（功能 #84）。
 *
 * 实现远程协助和接管功能。
 */
class RemoteAssistanceManager(private val context: Context) {

    private val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private val _sessionState = MutableStateFlow(SessionState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    /**
     * 会话状态
     */
    data class SessionState(
        val isActive: Boolean = false,
        val sessionId: String = "",
        val operatorId: String = "",
        val connectedAt: Long = 0,
        val isControlling: Boolean = false,
        val error: String? = null
    )

    /**
     * 远程会话
     */
    data class RemoteSession(
        val sessionId: String,
        val operatorId: String,
        val operatorName: String,
        val targetDeviceId: String,
        val status: SessionStatus = SessionStatus.PENDING,
        val startTime: Long = 0,
        val endTime: Long = 0,
        val isControlGranted: Boolean = false
    )

    enum class SessionStatus {
        PENDING, CONNECTED, DISCONNECTED, ERROR
    }

    /**
     * 来自协助端的远程输入事件。
     */
    sealed class RemoteInputEvent {
        /** 在 (x, y) 位置点击。 */
        data class Click(val x: Float, val y: Float) : RemoteInputEvent()

        /** 从 (startX, startY) 滑动到 (endX, endY)。 */
        data class Swipe(
            val startX: Float,
            val startY: Float,
            val endX: Float,
            val endY: Float,
            val durationMs: Long = 300
        ) : RemoteInputEvent()

        /** 向当前聚焦输入框写入文本。 */
        data class InputText(val text: String) : RemoteInputEvent()

        /** 全局返回。 */
        object Back : RemoteInputEvent()

        /** 全局主页。 */
        object Home : RemoteInputEvent()
    }

    private val _sessions = MutableStateFlow<List<RemoteSession>>(emptyList())
    val sessions: StateFlow<List<RemoteSession>> = _sessions.asStateFlow()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 发起远程协助请求
     */
    fun requestRemoteAssistance(operatorId: String, operatorName: String): RemoteSession {
        val session = RemoteSession(
            sessionId = "remote_${System.currentTimeMillis()}",
            operatorId = operatorId,
            operatorName = operatorName,
            targetDeviceId = "android_${System.currentTimeMillis()}",
            status = SessionStatus.PENDING
        )

        val current = _sessions.value.toMutableList()
        current.add(session)
        _sessions.value = current

        _sessionState.value = SessionState(
            isActive = true,
            sessionId = session.sessionId,
            operatorId = operatorId,
            connectedAt = System.currentTimeMillis()
        )

        return session
    }

    /**
     * 接受远程协助
     */
    fun acceptRemoteAssistance(sessionId: String): Boolean {
        val current = _sessions.value.toMutableList()
        val index = current.indexOfFirst { it.sessionId == sessionId }

        if (index >= 0) {
            current[index] = current[index].copy(
                status = SessionStatus.CONNECTED,
                startTime = System.currentTimeMillis()
            )
            _sessions.value = current
            return true
        }

        return false
    }

    /**
     * 拒绝远程协助
     */
    fun rejectRemoteAssistance(sessionId: String) {
        val current = _sessions.value.toMutableList()
        val index = current.indexOfFirst { it.sessionId == sessionId }

        if (index >= 0) {
            current[index] = current[index].copy(
                status = SessionStatus.DISCONNECTED,
                endTime = System.currentTimeMillis()
            )
            _sessions.value = current
        }

        _sessionState.value = SessionState()
    }

    /**
     * 结束远程会话
     */
    fun endSession(sessionId: String) {
        val current = _sessions.value.toMutableList()
        val index = current.indexOfFirst { it.sessionId == sessionId }

        if (index >= 0) {
            current[index] = current[index].copy(
                status = SessionStatus.DISCONNECTED,
                endTime = System.currentTimeMillis()
            )
            _sessions.value = current
        }

        _sessionState.value = SessionState()
    }

    /**
     * 授予控制权限
     */
    fun grantControl(sessionId: String): Boolean {
        val current = _sessions.value.toMutableList()
        val index = current.indexOfFirst { it.sessionId == sessionId }

        if (index >= 0) {
            current[index] = current[index].copy(isControlGranted = true)
            _sessions.value = current

            _sessionState.value = _sessionState.value.copy(isControlling = true)
            return true
        }

        return false
    }

    /**
     * 撤销控制权限
     */
    fun revokeControl(sessionId: String): Boolean {
        val current = _sessions.value.toMutableList()
        val index = current.indexOfFirst { it.sessionId == sessionId }

        if (index >= 0) {
            current[index] = current[index].copy(isControlGranted = false)
            _sessions.value = current

            _sessionState.value = _sessionState.value.copy(isControlling = false)
            return true
        }

        return false
    }

    /**
     * 获取活跃会话
     */
    fun getActiveSessions(): List<RemoteSession> {
        return _sessions.value.filter { it.status == SessionStatus.CONNECTED }
    }

    /**
     * 获取待处理会话
     */
    fun getPendingSessions(): List<RemoteSession> {
        return _sessions.value.filter { it.status == SessionStatus.PENDING }
    }

    /**
     * 处理远程输入事件（校验 sessionId 必须为当前活动会话，防止跨会话注入）
     */
    fun handleRemoteInput(sessionId: String, inputEvent: RemoteInputEvent) {
        val state = _sessionState.value
        if (state.isControlling && state.sessionId == sessionId) {
            executeRemoteInput(inputEvent)
        }
    }

    /**
     * 执行远程输入
     */
    private fun executeRemoteInput(inputEvent: RemoteInputEvent) {
        val service = com.jarvis.pineapple.accessibility.JarvisAccessibilityService.instance ?: return

        when (inputEvent) {
            is RemoteInputEvent.Click -> {
                // 优先使用无障碍手势精确点击坐标；失败时回退到节点点击
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val dispatched = service.performClick(inputEvent.x, inputEvent.y)
                    if (dispatched) return
                }
                val rootNode = service.rootInActiveWindow ?: return
                val clickableNode = findClickableNode(rootNode, inputEvent.x, inputEvent.y)
                clickableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            is RemoteInputEvent.Swipe -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    service.performSwipe(
                        inputEvent.startX, inputEvent.startY,
                        inputEvent.endX, inputEvent.endY, inputEvent.durationMs
                    )
                }
            }
            is RemoteInputEvent.InputText -> {
                val root = service.rootInActiveWindow ?: return
                val editText = findEditText(root)
                if (editText != null) {
                    val args = android.os.Bundle().apply {
                        putCharSequence(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            inputEvent.text
                        )
                    }
                    editText.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                        args
                    )
                } else {
                    service.performTextInput(inputEvent.text)
                }
            }
            RemoteInputEvent.Back -> service.performGlobalBack()
            RemoteInputEvent.Home -> service.performGlobalHome()
        }
    }

    private fun findClickableNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        x: Float,
        y: Float
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.contains(x.toInt(), y.toInt())) {
            if (node.isClickable) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findClickableNode(child, x, y)
                if (result != null) return result
            }
        }
        return null
    }

    private fun findEditText(
        node: android.view.accessibility.AccessibilityNodeInfo
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains("EditText") == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditText(child)
            if (result != null) return result
        }
        return null
    }

    /**
     * 创建屏幕录制授权 Intent（由 Activity 通过
     * `registerForActivityResult` 启动）。
     */
    fun createScreenCaptureIntent(): Intent = mediaProjectionManager.createScreenCaptureIntent()

    /**
     * 在用户授权后取得 MediaProjection。
     */
    fun onScreenCapturePermission(resultCode: Int, data: Intent?): Boolean {
        if (data == null) return false
        return runCatching {
            val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
            if (projection == null) return@runCatching false
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopScreenSharing()
                    }
                },
                Handler(Looper.getMainLooper())
            )
            mediaProjection = projection
            true
        }.getOrDefault(false)
    }

    /**
     * 开始屏幕共享（创建 VirtualDisplay + ImageReader 镜像真实屏幕）。
     */
    fun startScreenSharing(sessionId: String) {
        val projection = mediaProjection
        if (projection == null) {
            _sessionState.value = _sessionState.value.copy(error = "未授权屏幕录制")
            return
        }
        runCatching {
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val reader = ImageReader.newInstance(
                width, height, android.graphics.PixelFormat.RGBA_8888, 2
            )
            val display = projection.createVirtualDisplay(
                "jarvis-remote-$sessionId",
                width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            imageReader = reader
            virtualDisplay = display
            _sessionState.value = _sessionState.value.copy(
                isActive = true,
                sessionId = sessionId,
                connectedAt = System.currentTimeMillis(),
                error = null
            )
        }.onFailure {
            _sessionState.value = _sessionState.value.copy(error = "启动屏幕共享失败: ${it.message}")
        }
    }

    /**
     * 抓取当前屏幕帧原始像素（RGBA_8888），供远程端渲染。
     */
    fun captureScreenFrame(): ByteArray? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            bytes
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    /**
     * 停止屏幕共享
     */
    fun stopScreenSharing() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        _sessionState.value = SessionState()
    }

    /**
     * 获取会话统计
     */
    fun getSessionStatistics(): SessionStatistics {
        val allSessions = _sessions.value
        return SessionStatistics(
            totalSessions = allSessions.size,
            activeSessions = allSessions.count { it.status == SessionStatus.CONNECTED },
            pendingSessions = allSessions.count { it.status == SessionStatus.PENDING },
            completedSessions = allSessions.count { it.status == SessionStatus.DISCONNECTED },
            totalControlTime = allSessions.filter { it.isControlGranted }.sumOf {
                (it.endTime - it.startTime).coerceAtLeast(0)
            }
        )
    }

    data class SessionStatistics(
        val totalSessions: Int,
        val activeSessions: Int,
        val pendingSessions: Int,
        val completedSessions: Int,
        val totalControlTime: Long
    )

    /**
     * 清理资源
     */
    fun cleanup() {
        stopScreenSharing()
        _sessions.value = emptyList()
        _sessionState.value = SessionState()
    }
}

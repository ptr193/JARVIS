package com.jarvis.pineapple.assistant

import com.jarvis.pineapple.perception.CameraCaptureSession

object OverlayCameraController {
    const val EXTRA_TASK = "task"
    const val TASK_OBJECT = "object"
    const val TASK_DOCUMENT = "document"
    const val CAMERA_DENIED = "未授予相机权限"
    const val OPEN_FAILED = "无法打开相机"
    const val ENGINE_UNAVAILABLE = "识物引擎不可用"

    fun resolveTask(extra: String?): CameraCaptureSession.Task =
        if (extra == TASK_DOCUMENT) {
            CameraCaptureSession.Task.DOCUMENT
        } else {
            CameraCaptureSession.Task.OBJECT
        }

    fun titleFor(task: CameraCaptureSession.Task): String =
        if (task == CameraCaptureSession.Task.DOCUMENT) "扫文档" else "识物"

    fun noImageMessage(): String = CameraCaptureSession.NO_IMAGE
}

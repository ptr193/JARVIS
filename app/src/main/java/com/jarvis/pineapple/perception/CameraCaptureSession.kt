package com.jarvis.pineapple.perception

/**
 * 相机采集入口的权限门面与任务路由（#33/#34/#35/#94/#112/#113）。
 * 无权限或无图像时返回中文失败，禁止假成功。
 */
class CameraCaptureSession {

    enum class Task {
        FACE,
        IDENTITY,
        OBJECT,
        DOCUMENT,
        VISUAL_TRANSLATE,
        SCENE
    }

    data class Result(
        val success: Boolean,
        val message: String
    )

    fun interface ImageHandler {
        suspend fun invoke(image: ByteArray): String
    }

    fun interface IdentityHandler {
        suspend fun invoke(probe: ByteArray, reference: ByteArray): String
    }

    data class Handlers(
        val onFace: ImageHandler,
        val onIdentity: IdentityHandler,
        val onObject: ImageHandler,
        val onDocument: ImageHandler,
        val onVisualTranslate: ImageHandler,
        val onScene: ImageHandler
    )

    suspend fun run(
        permissionGranted: Boolean,
        image: ByteArray?,
        task: Task,
        handlers: Handlers,
        referenceImage: ByteArray? = null
    ): Result {
        gate(permissionGranted, image)?.let { return Result(success = false, message = it) }
        val bytes = image!!
        val message = try {
            when (task) {
                Task.FACE -> handlers.onFace.invoke(bytes)
                Task.OBJECT -> handlers.onObject.invoke(bytes)
                Task.DOCUMENT -> handlers.onDocument.invoke(bytes)
                Task.VISUAL_TRANSLATE -> handlers.onVisualTranslate.invoke(bytes)
                Task.SCENE -> handlers.onScene.invoke(bytes)
                Task.IDENTITY -> {
                    if (referenceImage == null || referenceImage.isEmpty()) {
                        IDENTITY_REFERENCE_MISSING
                    } else {
                        handlers.onIdentity.invoke(bytes, referenceImage)
                    }
                }
            }
        } catch (e: Exception) {
            "采集处理失败：${e.message ?: e.javaClass.simpleName}"
        }
        val success = message != IDENTITY_REFERENCE_MISSING && !message.startsWith("采集处理失败")
        return Result(success = success, message = message)
    }

    companion object {
        const val CAMERA_PERMISSION_DENIED = ObjectDetector.CAMERA_PERMISSION_DENIED
        const val NO_IMAGE = "未采集到图像，请先拍照或授权相机"
        const val IDENTITY_REFERENCE_MISSING = "身份比对需要两张人脸照片，请先采集参考人脸再拍摄待比对照片"

        fun gate(permissionGranted: Boolean, image: ByteArray?): String? {
            ObjectDetector.ensureCameraPermission(permissionGranted)?.let { return it }
            if (image == null || image.isEmpty()) return NO_IMAGE
            return null
        }
    }
}

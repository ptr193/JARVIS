package com.jarvis.pineapple.perception

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCaptureSessionTest {

    private val image = byteArrayOf(1, 2, 3, 4)
    private val session = CameraCaptureSession()
    private val handlers = CameraCaptureSession.Handlers(
        onFace = CameraCaptureSession.ImageHandler { "人脸:${it.size}" },
        onIdentity = CameraCaptureSession.IdentityHandler { probe, reference ->
            "比对:${probe.size}:${reference.size}"
        },
        onObject = CameraCaptureSession.ImageHandler { "物体:${it.size}" },
        onDocument = CameraCaptureSession.ImageHandler { "文档:${it.size}" },
        onVisualTranslate = CameraCaptureSession.ImageHandler { "翻译:${it.size}" },
        onScene = CameraCaptureSession.ImageHandler { "场景:${it.size}" }
    )

    @Test
    fun `denied camera permission returns chinese error and does not invoke detector`() = runBlocking {
        var invoked = false
        val blocked = handlers.copy(
            onFace = CameraCaptureSession.ImageHandler {
                invoked = true
                "不应执行"
            }
        )
        val result = session.run(
            permissionGranted = false,
            image = image,
            task = CameraCaptureSession.Task.FACE,
            handlers = blocked
        )
        assertFalse(result.success)
        assertEquals(CameraCaptureSession.CAMERA_PERMISSION_DENIED, result.message)
        assertTrue(result.message.contains("无相机权限"))
        assertFalse(invoked)
    }

    @Test
    fun `missing image returns chinese error`() = runBlocking {
        val result = session.run(
            permissionGranted = true,
            image = null,
            task = CameraCaptureSession.Task.OBJECT,
            handlers = handlers
        )
        assertFalse(result.success)
        assertEquals(CameraCaptureSession.NO_IMAGE, result.message)
    }

    @Test
    fun `granted permission with image invokes object detector`() = runBlocking {
        val result = session.run(
            permissionGranted = true,
            image = image,
            task = CameraCaptureSession.Task.OBJECT,
            handlers = handlers
        )
        assertTrue(result.success)
        assertEquals("物体:4", result.message)
    }

    @Test
    fun `identity comparison requires two images`() = runBlocking {
        val missing = session.run(
            permissionGranted = true,
            image = image,
            task = CameraCaptureSession.Task.IDENTITY,
            handlers = handlers,
            referenceImage = null
        )
        assertFalse(missing.success)
        assertEquals(CameraCaptureSession.IDENTITY_REFERENCE_MISSING, missing.message)

        val ok = session.run(
            permissionGranted = true,
            image = image,
            task = CameraCaptureSession.Task.IDENTITY,
            handlers = handlers,
            referenceImage = byteArrayOf(9, 8)
        )
        assertTrue(ok.success)
        assertEquals("比对:4:2", ok.message)
    }

    @Test
    fun `document scan and visual translate routes are reachable`() = runBlocking {
        val doc = session.run(true, image, CameraCaptureSession.Task.DOCUMENT, handlers)
        val vis = session.run(true, image, CameraCaptureSession.Task.VISUAL_TRANSLATE, handlers)
        val scene = session.run(true, image, CameraCaptureSession.Task.SCENE, handlers)
        assertEquals("文档:4", doc.message)
        assertEquals("翻译:4", vis.message)
        assertEquals("场景:4", scene.message)
    }
}

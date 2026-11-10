package com.jarvis.pineapple.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.pineapple.R
import com.jarvis.pineapple.info.InfoProcessor
import com.jarvis.pineapple.perception.CameraCaptureSession
import com.jarvis.pineapple.perception.ObjectDetector
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

class OverlayCameraActivity : ComponentActivity() {
    private val session = CameraCaptureSession()
    private lateinit var status: TextView
    private lateinit var task: CameraCaptureSession.Task

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            status.text = OverlayCameraController.noImageMessage()
            return@registerForActivityResult
        }
        process(bitmap)
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            status.text = OverlayCameraController.CAMERA_DENIED
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.overlay_camera)
        status = findViewById(R.id.overlay_camera_status)
        val title = findViewById<TextView>(R.id.overlay_camera_title)
        findViewById<Button>(R.id.overlay_camera_close).setOnClickListener { finish() }

        task = OverlayCameraController.resolveTask(intent.getStringExtra(OverlayCameraController.EXTRA_TASK))
        title.text = OverlayCameraController.titleFor(task)
        status.text = "正在打开相机"

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        runCatching { takePicture.launch(null) }
            .onFailure { status.text = OverlayCameraController.OPEN_FAILED }
    }

    private fun process(bitmap: Bitmap) {
        lifecycleScope.launch {
            val jpeg = withContext(Dispatchers.Default) { toJpeg(bitmap) }
            val result = withContext(Dispatchers.Default) {
                val handlers = buildHandlers() ?: return@withContext CameraCaptureSession.Result(
                    success = false,
                    message = OverlayCameraController.ENGINE_UNAVAILABLE
                )
                session.run(
                    permissionGranted = true,
                    image = jpeg,
                    task = task,
                    handlers = handlers
                )
            }
            status.text = result.message
        }
    }

    private fun buildHandlers(): CameraCaptureSession.Handlers? {
        val objectDetector = runCatching { GlobalContext.get().get<ObjectDetector>() }.getOrNull()
        val infoProcessor = runCatching { GlobalContext.get().get<InfoProcessor>() }.getOrNull()
        if (objectDetector == null || infoProcessor == null) return null
        return CameraCaptureSession.Handlers(
            onFace = CameraCaptureSession.ImageHandler { "不支持此人脸任务" },
            onIdentity = CameraCaptureSession.IdentityHandler { _, _ -> "不支持此身份任务" },
            onObject = CameraCaptureSession.ImageHandler { bytes ->
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@ImageHandler "图像解码失败"
                val objects = objectDetector.detect(image)
                if (objects.isEmpty()) "未检测到物体"
                else objects.joinToString("；") { "${it.label} ${(it.confidence * 100).toInt()}%" }
            },
            onDocument = CameraCaptureSession.ImageHandler { bytes ->
                val reconstructed = infoProcessor.reconstruct2D(listOf(bytes))
                if (reconstructed.success) reconstructed.message
                else reconstructed.message.ifBlank { "文档扫描失败" }
            },
            onVisualTranslate = CameraCaptureSession.ImageHandler { "不支持此翻译任务" },
            onScene = CameraCaptureSession.ImageHandler { "不支持此场景任务" }
        )
    }

    private fun toJpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    companion object {
        const val EXTRA_TASK = OverlayCameraController.EXTRA_TASK
        const val TASK_OBJECT = OverlayCameraController.TASK_OBJECT
        const val TASK_DOCUMENT = OverlayCameraController.TASK_DOCUMENT
    }
}

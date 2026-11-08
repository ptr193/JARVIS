package com.jarvis.pineapple.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.pineapple.chat.TranslationEngine
import com.jarvis.pineapple.info.InfoProcessor
import com.jarvis.pineapple.perception.CameraCaptureSession
import com.jarvis.pineapple.perception.ObjectDetector
import com.jarvis.pineapple.security.SecurityManager
import com.jarvis.pineapple.system.PermissionManager
import com.jarvis.pineapple.ui.permission.rememberOnDemandPermission
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

@Composable
fun CameraCaptureSection(
    showMsg: (String) -> Unit,
    showFaceActions: Boolean,
    showPerceptionActions: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { CameraCaptureSession() }
    val objectDetector = remember { GlobalContext.get().get<ObjectDetector>() }
    val security = remember { GlobalContext.get().get<SecurityManager>() }
    val infoProcessor = remember { GlobalContext.get().get<InfoProcessor>() }
    val translator = remember { GlobalContext.get().get<TranslationEngine>() }

    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var jpeg by remember { mutableStateOf<ByteArray?>(null) }
    var referenceJpeg by remember { mutableStateOf<ByteArray?>(null) }
    var status by remember { mutableStateOf("尚未拍照") }

    val handlers = remember {
        CameraCaptureSession.Handlers(
            onFace = CameraCaptureSession.ImageHandler { bytes ->
                val result = security.detectFaces(bytes)
                if (result.isDetected) {
                    "检测到 ${result.faceCount} 张人脸，置信度 ${(result.confidence * 100).toInt()}%"
                } else {
                    "未检测到人脸"
                }
            },
            onIdentity = CameraCaptureSession.IdentityHandler { probe, reference ->
                val cmp = security.compareFaces(probe, reference)
                "${if (cmp.isMatch) "身份匹配" else "身份不匹配"} · ${cmp.details}"
            },
            onObject = CameraCaptureSession.ImageHandler { bytes ->
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@ImageHandler "图像解码失败"
                val objects = objectDetector.detect(bitmap)
                if (objects.isEmpty()) "未检测到物体"
                else objects.joinToString("；") { "${it.label} ${(it.confidence * 100).toInt()}%" }
            },
            onDocument = CameraCaptureSession.ImageHandler { bytes ->
                val result = infoProcessor.reconstruct2D(listOf(bytes))
                if (result.success) result.message else (result.message.ifBlank { "文档扫描失败" })
            },
            onVisualTranslate = CameraCaptureSession.ImageHandler { bytes ->
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@ImageHandler "图像解码失败"
                val objects = objectDetector.detect(bitmap)
                val labels = objects.map { it.label }.filter { it.isNotBlank() && it != "Unknown" }
                if (labels.isEmpty()) {
                    "未检测到画面文字或可翻译标签"
                } else {
                    val source = labels.joinToString(" ")
                    val translated = translator.translate(source, targetLanguage = "zh")
                    "原文：$source\n译文：${translated.translatedText}"
                }
            },
            onScene = CameraCaptureSession.ImageHandler { bytes ->
                val result = infoProcessor.reconstruct2D(listOf(bytes))
                if (result.success) "场景还原（单视角）：${result.message}"
                else result.message.ifBlank { "场景还原失败" }
            }
        )
    }

    fun hasCameraPermission(): Boolean =
        PermissionManager.hasPermissions(context, PermissionManager.CAMERA_PERMISSIONS)

    fun runTask(task: CameraCaptureSession.Task) {
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                session.run(
                    permissionGranted = hasCameraPermission(),
                    image = jpeg,
                    task = task,
                    handlers = handlers,
                    referenceImage = referenceJpeg
                )
            }
            status = result.message
            showMsg(result.message)
        }
    }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            status = CameraCaptureSession.NO_IMAGE
            showMsg(status)
            return@rememberLauncherForActivityResult
        }
        preview = bitmap
        jpeg = bitmapToJpeg(bitmap)
        status = "已采集一帧（${jpeg?.size ?: 0} 字节）"
        showMsg(status)
    }

    val requestCamera = rememberOnDemandPermission(
        permissions = PermissionManager.CAMERA_PERMISSIONS,
        onGranted = { takePicture.launch(null) },
        onDenied = { reason ->
            status = reason
            showMsg(reason)
        }
    )

    fun capture() {
        if (hasCameraPermission()) takePicture.launch(null)
        else requestCamera()
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("相机采集", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(status, fontSize = 13.sp)
            preview?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "最近采集预览",
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )
            }
            Row {
                Button(onClick = { capture() }) { Text("打开相机拍照") }
                if (showFaceActions) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        referenceJpeg = jpeg
                        status = if (jpeg == null) CameraCaptureSession.NO_IMAGE else "已将当前照片设为身份比对参考"
                        showMsg(status)
                    }) { Text("设为参考人脸") }
                }
            }
            if (showFaceActions) {
                Row {
                    Button(onClick = { runTask(CameraCaptureSession.Task.FACE) }) { Text("人脸识别") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { runTask(CameraCaptureSession.Task.IDENTITY) }) { Text("身份比对") }
                }
            }
            if (showPerceptionActions) {
                Row {
                    Button(onClick = { runTask(CameraCaptureSession.Task.OBJECT) }) { Text("物体检测") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { runTask(CameraCaptureSession.Task.DOCUMENT) }) { Text("文档扫描") }
                }
                Row {
                    Button(onClick = { runTask(CameraCaptureSession.Task.VISUAL_TRANSLATE) }) { Text("视觉翻译") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { runTask(CameraCaptureSession.Task.SCENE) }) { Text("场景还原") }
                }
            }
        }
    }
}

private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    return out.toByteArray()
}

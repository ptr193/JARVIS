package com.jarvis.pineapple.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * 截屏管理器。
 *
 * 使用 MediaProjection API 实现屏幕截图功能。
 * 需要用户授权屏幕录制权限。
 */
class ScreenshotManager(private val context: Context) {

    private val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 请求屏幕录制权限
     */
    fun requestCapturePermission(activity: Activity, requestCode: Int = 1001) {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        activity.startActivityForResult(intent, requestCode)
    }

    /**
     * 处理权限结果
     */
    fun handlePermissionResult(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            return false
        }

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        return mediaProjection != null
    }

    /**
     * 截取屏幕
     */
    fun captureScreenshot(outputFile: File? = null): Bitmap? {
        if (mediaProjection == null) {
            return null
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)

        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "Screenshot",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )

        // 等待图像可用
        var retryCount = 0
        var bitmap: Bitmap? = null

        while (retryCount < 10 && bitmap == null) {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                bitmap = imageToBitmap(image)
                image.close()
            } else {
                Thread.sleep(100)
                retryCount++
            }
        }

        // 保存文件
        bitmap?.let { bmp ->
            val file = outputFile ?: File(context.cacheDir, "screenshot_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }

        return bitmap
    }

    /**
     * 截屏并保存到文件，返回文件；未授权或失败返回 null。
     */
    fun captureToFile(outputFile: File? = null): File? {
        val file = outputFile ?: File(context.cacheDir, "screenshot_${System.currentTimeMillis()}.png")
        captureScreenshot(file) ?: return null
        return file.takeIf { it.exists() && it.length() > 0 }
    }

    /** 是否已获得 MediaProjection 授权。 */
    fun hasPermission(): Boolean = mediaProjection != null

    /**
     * 将 Image 转换为 Bitmap
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        // 去除行填充，裁剪为实际屏幕宽度
        return if (padded.width > image.width) {
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            cropped
        } else {
            padded
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    /**
     * 检查是否正在捕获
     */
    fun isCapturing(): Boolean {
        return _isCapturing.value
    }
}

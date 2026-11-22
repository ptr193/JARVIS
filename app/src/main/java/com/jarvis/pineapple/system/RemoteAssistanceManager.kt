package com.jarvis.pineapple.system

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.core.content.FileProvider
import java.io.File

/**
 * 远程协助 / 屏幕共享（#67 / #68 / #71）。
 *
 * 真实能力：通过 MediaProjection 捕获屏幕，并把截图文件通过 FileProvider + Sharesheet 分享出去。
 * 边界说明：跨设备“远程协助 / 远程控制”需要服务端中继，本应用不含中继服务，
 * 因此这里只提供屏幕捕获与分享，绝不伪装为“已建立远程会话”。
 * 失败一律返回 null/false，并由调用方展示失败信息。
 */
class RemoteAssistanceManager(
    private val context: Context,
    private val screenshot: ScreenshotManager
) {
    fun createScreenCaptureIntent(): Intent =
        (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .createScreenCaptureIntent()

    fun onCaptureConsent(resultCode: Int, data: Intent?): Boolean =
        screenshot.handlePermissionResult(resultCode, data)

    /** 捕获一帧屏幕并保存为文件；未授权或失败返回 null。 */
    fun captureScreenToFile(): File? = screenshot.captureToFile()

    fun hasCapturePermission(): Boolean = screenshot.hasPermission()

    fun stopScreenSharing() = screenshot.release()

    /** 构造分享截图的 Intent；文件无效返回 null。 */
    fun buildShareIntent(file: File?): Intent? {
        if (!isShareable(file)) return null
        return try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file!!
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** 纯逻辑：文件是否可分享，便于单测。 */
        fun isShareable(file: File?): Boolean =
            file != null && file.exists() && file.length() > 0
    }
}

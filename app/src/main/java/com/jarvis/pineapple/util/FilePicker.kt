package com.jarvis.pineapple.util

import android.app.Activity
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * SAF (Storage Access Framework) 文件选择器工具类
 * 用于替代已废弃的 startActivityForResult，支持 Android 10+ 分区存储
 */
class FilePicker(private val activity: ComponentActivity) {

    private val pickSingleFile = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        _pickResult.complete(uri)
    }

    private val pickMultipleFiles = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        _pickMultipleResult.complete(uris)
    }

    private val pickDirectory = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        _pickDirectoryResult.complete(uri)
    }

    private var _pickResult = CompletableDeferred<android.net.Uri?>()
    private var _pickMultipleResult = CompletableDeferred<List<android.net.Uri>?>()
    private var _pickDirectoryResult = CompletableDeferred<android.net.Uri?>()

    /**
     * 选择单个文件
     * @param mimeTypes MIME 类型数组，如 arrayOf("application/octet-stream", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
     * @return 选中的文件 URI，用户取消返回 null
     */
    suspend fun pickFile(mimeTypes: Array<String> = arrayOf("*/*")): android.net.Uri? {
        _pickResult = CompletableDeferred()
        pickSingleFile.launch(mimeTypes)
        return _pickResult.await()
    }

    /**
     * 选择多个文件
     */
    suspend fun pickFiles(mimeTypes: Array<String> = arrayOf("*/*")): List<android.net.Uri> {
        _pickMultipleResult = CompletableDeferred()
        pickMultipleFiles.launch(mimeTypes)
        return _pickMultipleResult.await() ?: emptyList()
    }

    /**
     * 选择目录（用于导出/备份路径选择）
     */
    suspend fun pickDir(): android.net.Uri? {
        _pickDirectoryResult = CompletableDeferred()
        pickDirectory.launch(null)
        return _pickDirectoryResult.await()
    }

    /**
     * 扩展函数：在 ComponentActivity 中快速创建 FilePicker
     */
    companion object {
        fun create(activity: ComponentActivity): FilePicker = FilePicker(activity)
    }
}

/**
 * 常用 MIME 类型常量
 */
object MimeTypes {
    // 虚拟机镜像格式
    const val VM_IMAGE = "application/octet-stream"
    // 模型文件格式
    const val MODEL_LITERTLM = "application/octet-stream"
    const val MODEL_GGUF = "application/octet-stream"
    const val MODEL_ONNX = "application/octet-stream"
    const val MODEL_PYTORCH = "application/octet-stream"
    // 通用
    const val ALL = "*/*"
}

package com.jarvis.pineapple.ui.model

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.pineapple.model.ModelDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import org.koin.androidx.compose.get

/**
 * 模型选择界面。
 *
 * 功能：
 * - 显示所有可用模型
 * - 选择当前使用的模型
 * - 显示模型状态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionScreen(
    onBack: () -> Unit,
    availableModels: List<com.jarvis.pineapple.model.AiModel>,
    currentModelId: String,
    onModelSelected: (String) -> Unit
) {
    var selectedModelId by remember { mutableStateOf(currentModelId) }
    val modelDownloadManager = get<ModelDownloadManager>()
    val modelImportManager = get<com.jarvis.pineapple.model.ModelImportManager>()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val context = LocalContext.current

    fun showMsg(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    /**
     * 将 SAF Uri 的内容复制到应用私有目录
     */
    fun copyUriToLocalFile(uri: android.net.Uri): File? {
        return try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri) ?: return null

            val displayName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            } ?: "imported_model"

            val localFile = File(context.filesDir, "models/${System.currentTimeMillis()}_$displayName")
            localFile.parentFile?.mkdirs()

            val outputStream = FileOutputStream(localFile)
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            localFile
        } catch (e: Exception) {
            android.util.Log.e("ModelSelectionScreen", "copyUriToLocalFile failed", e)
            null
        }
    }

    /**
     * 导入本地模型文件到模型管理器
     */
    fun importLocalModel(file: File): Boolean {
        // 从文件名生成 modelId
        val modelId = file.nameWithoutExtension.lowercase().replace(" ", "_")
        val modelName = file.nameWithoutExtension
        return modelDownloadManager.importLocalModel(file, modelId, modelName)
    }

    // SAF 文件选择器：导入本地模型文件（多格式：GGUF/ONNX/Safetensors/PyTorch/LiteRT-LM）
    val importModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            scope.launch {
                val displayName = context.contentResolver.query(selectedUri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) cursor.getString(nameIndex) else "imported_model"
                    } else "imported_model"
                } ?: "imported_model"
                showMsg("正在导入 $displayName …")
                val result = modelImportManager.importFromUri(selectedUri, displayName)
                if (result.success) {
                    showMsg("模型导入成功: $displayName")
                } else {
                    showMsg("模型导入失败: ${result.message}")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择模型") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                "当前模型",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(availableModels) { model ->
                    ModelItem(
                        model = model,
                        isSelected = model.info.id == selectedModelId,
                        onClick = {
                            selectedModelId = model.info.id
                            onModelSelected(model.info.id)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "本地模型",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(availableModels.filter { it.info.source == com.jarvis.pineapple.model.ModelSource.LOCAL_LITERT }) { model ->
                    ModelItem(
                        model = model,
                        isSelected = model.info.id == selectedModelId,
                        onClick = {
                            selectedModelId = model.info.id
                            onModelSelected(model.info.id)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "API 模型",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(availableModels.filter { it.info.source == com.jarvis.pineapple.model.ModelSource.API }) { model ->
                    ModelItem(
                        model = model,
                        isSelected = model.info.id == selectedModelId,
                        onClick = {
                            selectedModelId = model.info.id
                            onModelSelected(model.info.id)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "服务器模型",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(availableModels.filter { it.info.source == com.jarvis.pineapple.model.ModelSource.SERVER }) { model ->
                    ModelItem(
                        model = model,
                        isSelected = model.info.id == selectedModelId,
                        onClick = {
                            selectedModelId = model.info.id
                            onModelSelected(model.info.id)
                        }
                    )
                }
            }

            // 导入本地模型按钮
            androidx.compose.material3.Button(
                onClick = { importModelLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Text("导入本地模型文件 (.litertlm / .gguf / .onnx)")
            }
        }
    }
}

@Composable
private fun ModelItem(
    model: com.jarvis.pineapple.model.AiModel,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick
            )

            Spacer(modifier = Modifier.padding(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    model.info.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    model.info.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    when (model.state) {
                        com.jarvis.pineapple.model.ModelState.READY -> "就绪"
                        com.jarvis.pineapple.model.ModelState.LOADING -> "加载中"
                        com.jarvis.pineapple.model.ModelState.ERROR -> "错误"
                        com.jarvis.pineapple.model.ModelState.UNLOADED -> "未加载"
                    },
                    fontSize = 12.sp,
                    color = when (model.state) {
                        com.jarvis.pineapple.model.ModelState.READY -> MaterialTheme.colorScheme.primary
                        com.jarvis.pineapple.model.ModelState.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

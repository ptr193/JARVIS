package com.jarvis.pineapple.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.pineapple.model.ModelDownloadManager
import com.jarvis.pineapple.model.OnboardingGate
import kotlinx.coroutines.launch

@Composable
fun ModelOnboardingScreen(
    downloadManager: ModelDownloadManager,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configs = remember { downloadManager.getModelConfigs() }
    var selectedId by remember { mutableStateOf(configs.firstOrNull()?.id.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    val states by downloadManager.downloadStates.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("首次模型安装", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "本地尚无可用模型。请选择一个默认模型并确认后开始下载。未确认前不会自动下载。",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        configs.forEach { config ->
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                RadioButton(
                    selected = selectedId == config.id,
                    onClick = { selectedId = config.id }
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(config.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "约 ${config.sizeBytes / (1024 * 1024)} MB",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        val progress = states[selectedId]
        if (downloading && progress != null) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { progress.progress }, modifier = Modifier.fillMaxWidth())
            Text("下载中 ${(progress.progress * 100).toInt()}%", fontSize = 12.sp)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (selectedId.isBlank()) {
                    error = "请先选择一个模型"
                    return@Button
                }
                context.getSharedPreferences(OnboardingGate.PREFS, android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean(OnboardingGate.KEY_CONFIRMED, true).apply()
                downloading = true
                error = null
                scope.launch {
                    val ok = downloadManager.downloadModel(selectedId)
                    downloading = false
                    if (ok) {
                        onFinished()
                    } else {
                        val cause = downloadManager.getDownloadProgress(selectedId)?.errorMessage
                        error = if (cause.isNullOrBlank()) OnboardingGate.DOWNLOAD_FAILED else "模型下载失败：$cause"
                    }
                }
            },
            enabled = !downloading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (error != null) "重试下载" else "开始下载") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                context.getSharedPreferences(OnboardingGate.PREFS, android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean(OnboardingGate.KEY_SKIPPED, true).apply()
                onFinished()
            },
            enabled = !downloading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("稍后安装，先进入对话") }
    }
}

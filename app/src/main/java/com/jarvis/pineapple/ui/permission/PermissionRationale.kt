package com.jarvis.pineapple.ui.permission

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jarvis.pineapple.system.PermissionExplainer
import com.jarvis.pineapple.system.PermissionManager

private const val PERMISSION_PREFS = "jarvis_permissions"
private const val KEY_PROMPTED = "startup_prompted_v1"

/**
 * 首次启动时主动弹出权限说明并批量申请运行时权限。
 *
 * 之前权限只在具体功能触发时按需申请，冷启动没有任何弹窗，导致麦克风/通知/电话等
 * 权限长期缺失。此组件在每个进程首次进入主界面时检查并集中申请。
 */
@Composable
fun StartupPermissionRequest() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
    }
    val missing = remember {
        PermissionManager.coreRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }
    var visible by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        prefs.edit().putBoolean(KEY_PROMPTED, true).apply()
        visible = false
    }

    LaunchedEffect(Unit) {
        if (missing.isNotEmpty() && !prefs.getBoolean(KEY_PROMPTED, false)) {
            visible = true
        }
    }

    if (!visible) return

    AlertDialog(
        onDismissRequest = {
            prefs.edit().putBoolean(KEY_PROMPTED, true).apply()
            visible = false
        },
        title = { Text("授予必要权限") },
        text = {
            Column {
                Text(PermissionExplainer.rationaleFor(missing))
                Spacer(Modifier.height(8.dp))
                Text(
                    "无障碍服务无法通过弹窗授予，如需使用请在系统设置中手动开启；其余权限可稍后在设置中补充。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit().putBoolean(KEY_PROMPTED, true).apply()
                visible = false
                launcher.launch(missing)
            }) { Text("立即授权") }
        },
        dismissButton = {
            TextButton(onClick = {
                prefs.edit().putBoolean(KEY_PROMPTED, true).apply()
                visible = false
            }) { Text("稍后") }
        }
    )
}

@Composable
fun rememberOnDemandPermission(
    permissions: Array<String>,
    onGranted: () -> Unit,
    onDenied: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = permissions.all { result[it] == true } ||
            PermissionManager.hasPermissions(context, permissions)
        if (granted) onGranted() else onDenied("未授予权限")
    }
    if (pending) {
        AlertDialog(
            onDismissRequest = { pending = false },
            title = { Text("需要权限") },
            text = { Text(PermissionExplainer.rationaleFor(permissions)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = false
                    launcher.launch(permissions)
                }) { Text("继续授权") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pending = false
                    onDenied("未授予权限")
                }) { Text("取消") }
            }
        )
    }
    return {
        if (PermissionManager.hasPermissions(context, permissions)) onGranted()
        else pending = true
    }
}

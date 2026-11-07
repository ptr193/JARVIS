package com.jarvis.pineapple.ui.permission

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.jarvis.pineapple.system.PermissionExplainer
import com.jarvis.pineapple.system.PermissionManager

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

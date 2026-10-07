package com.jarvis.pineapple.ui.vm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 虚拟机界面
 * 顶部：虚拟机名称 + 功能按钮
 * 四个标签：软件包 / 可下载发行版 / 已下载 / 文件管理
 * 底部：导入系统镜像 + 输入框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualMachineScreen(onBack: () -> Unit) {
    val tabs = listOf("软件包", "可下载发行版", "已下载", "文件管理")
    var selected by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("虚拟机") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selected) {
                tabs.forEachIndexed { i, title ->
                    Tab(
                        selected = selected == i,
                        onClick = { selected = i },
                        text = { Text(title) }
                    )
                }
            }
            // 标签内容
            when (selected) {
                0 -> PackageTab()
                1 -> DownloadableDistrosTab()
                2 -> DownloadedTab()
                3 -> FileManagerTab()
            }
            Spacer(Modifier.weight(1f))
            // 底部：导入镜像 + 终端输入
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Button(onClick = { /* 导入系统镜像文件 */ }, modifier = Modifier.fillMaxWidth()) {
                    Text("导入系统镜像文件")
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    var cmd by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = cmd,
                        onValueChange = { cmd = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入命令...") }
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { /* 执行命令 */ }) { Text("执行") }
                }
            }
        }
    }
}

@Composable
private fun PackageTab() {
    Text("软件包管理", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(listOf("curl", "git", "vim", "python3", "nodejs")) { pkg ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(pkg)
                Button(onClick = {}) { Text("安装") }
            }
        }
    }
}

@Composable
private fun DownloadableDistrosTab() {
    Text("可下载的 Linux 发行版", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
    val distros = listOf("Ubuntu 24.04", "Debian 12", "Fedora 40", "Arch Linux", "Alpine")
    LazyColumn {
        items(distros) { d ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(d)
                Button(onClick = {}) { Text("下载") }
            }
        }
    }
}

@Composable
private fun DownloadedTab() {
    Text("已下载的发行版", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
    Text("暂无已下载发行版", modifier = Modifier.padding(16.dp))
}

@Composable
private fun FileManagerTab() {
    Text("文件管理", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
    LazyColumn {
        items(listOf("/home", "/etc", "/root", "/tmp")) { path ->
            Text(path, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

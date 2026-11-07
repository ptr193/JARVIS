package com.jarvis.pineapple.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jarvis.pineapple.model.ModelScheduler
import com.jarvis.pineapple.service.KeepAliveService
import com.jarvis.pineapple.speech.SpeechManager
import com.jarvis.pineapple.speech.VoiceWakeManager
import com.jarvis.pineapple.wake.AppIconWakeDetector
import com.jarvis.pineapple.wake.NotificationWakeDetector
import com.jarvis.pineapple.wake.TileWakeDetector
import com.jarvis.pineapple.wake.WakeEngine
import com.jarvis.pineapple.wake.WakeSource
import com.jarvis.pineapple.model.ModelDownloadManager
import com.jarvis.pineapple.model.OnboardingGate
import com.jarvis.pineapple.ui.chat.ChatScreen
import com.jarvis.pineapple.ui.model.ModelSelectionScreen
import com.jarvis.pineapple.ui.onboarding.ModelOnboardingScreen
import com.jarvis.pineapple.ui.settings.SettingsScreen
import com.jarvis.pineapple.ui.theme.JarvisTheme
import com.jarvis.pineapple.ui.vm.VirtualMachineScreen
import com.jarvis.pineapple.ui.voice.VoiceChatScreen
import kotlinx.coroutines.launch
import org.koin.androidx.compose.get

class MainActivity : ComponentActivity() {

    private val iconWakeDetector by lazy { AppIconWakeDetector(this) }
    private val notificationWakeDetector by lazy {
        org.koin.core.context.GlobalContext.get().get<NotificationWakeDetector>()
    }
    private val tileWakeDetector by lazy {
        org.koin.core.context.GlobalContext.get().get<TileWakeDetector>()
    }
    private val wakeEngine by lazy {
        org.koin.core.context.GlobalContext.get().get<WakeEngine>()
    }

    /** 唤醒引擎初始化用的后台作用域，避免占用主线程。 */
    private val wakeInitScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { com.jarvis.pineapple.util.DownloadLogger.log("MainActivity", "onCreate 开始") }
        // 注册应用图标快捷方式并处理本次启动的双击/长按唤醒
        runCatching { iconWakeDetector.start() }
        // 启动唤醒引擎（汇总语音/电源键/通知/摇一摇/蓝牙/磁贴/图标/Home 长按 8 种唤醒源）。
        // 任一检测器初始化失败（如麦克风权限未授予）都不应拖垮应用启动，故逐项隔离。
        // 放到后台线程执行：唤醒器初始化与 Compose 首帧无关，不应占用主线程
        // （实机日志显示其在主线程上因类校验耗时 7.8 秒）。
        wakeInitScope.launch { runCatching { wakeEngine.start() } }
        runCatching { applySavedWakePreferences() }
        runCatching { handleIconWake(intent) }
        runCatching { handleWakeIntent(intent) }
        enableEdgeToEdge()
        setContent { JarvisTheme { Surface(modifier = Modifier.fillMaxSize()) { JarvisApp() } } }
        runCatching { com.jarvis.pineapple.util.DownloadLogger.log("MainActivity", "setContent 完成，等待首帧") }
        // 保活前台服务延后到“首帧渲染完成”之后再启动。
        //
        // 原因：startForegroundService() 之后系统要求约 5 秒内调用 startForeground()，
        // 而冷启动时主线程会被 Compose 首帧/类校验长时间占满，导致服务回调排队超时，
        // 抛 RemoteServiceException 使应用启动即崩溃（BUG-2026-1025）。
        // 等到首帧绘制完成再启动，此时主线程已空闲，可确保在时限内完成。
        window.decorView.post {
            runCatching { com.jarvis.pineapple.util.DownloadLogger.log("MainActivity", "首帧后启动 KeepAliveService") }
            KeepAliveService.startSafely(this, TAG)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runCatching { handleIconWake(intent) }
        runCatching { handleWakeIntent(intent) }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    /** 从设置持久化的开关恢复唤醒源启停状态（通用设置 → 唤醒方式）。 */
    private fun applySavedWakePreferences() {
        val prefs = getSharedPreferences("jarvis_general", MODE_PRIVATE)
        fun apply(source: WakeSource, key: String, default: Boolean) {
            runCatching { wakeEngine.setEnabled(source, prefs.getBoolean(key, default)) }
        }
        apply(WakeSource.VOICE, "voice_wake", true)
        apply(WakeSource.POWER_BUTTON, "power_wake", true)
        apply(WakeSource.SHAKE, "shake_wake", true)
        apply(WakeSource.BLUETOOTH, "bluetooth_wake", true)
        // 云端总开关恢复
        runCatching {
            org.koin.core.context.GlobalContext.get()
                .get<com.jarvis.pineapple.cloud.CloudSyncManager>()
                .setCloudEnabled(prefs.getBoolean("cloud_sync", false))
        }
    }

    /** 处理来自通知唤醒按钮 / 快捷磁贴的 Intent，转为 WakeEngine 唤醒事件。 */
    private fun handleWakeIntent(intent: Intent?) {        when (intent?.action) {
            NotificationWakeDetector.ACTION_WAKE -> {
                notificationWakeDetector.onWakeAction()
                if (notificationWakeDetector.consume()) {
                    wakeEngine.trigger(WakeSource.NOTIFICATION)
                }
            }
            TileWakeDetector.ACTION_TILE_WAKE -> {
                tileWakeDetector.onTileClick()
                if (tileWakeDetector.consume()) {
                    wakeEngine.trigger(WakeSource.TILE)
                }
            }
        }
    }

    /** 将图标双击/长按转换为 WakeEngine 唤醒事件。 */
    private fun handleIconWake(intent: Intent?) {
        val longPress = intent?.getBooleanExtra(AppIconWakeDetector.EXTRA_LONG_PRESS_WAKE, false) == true
        iconWakeDetector.onIconAction(
            if (longPress) AppIconWakeDetector.ACTION_LONG_PRESS else AppIconWakeDetector.ACTION_TAP
        )
        if (iconWakeDetector.consume()) {
            runCatching {
                org.koin.core.context.GlobalContext.get()
                    .get<WakeEngine>()
                    .trigger(WakeSource.APP_ICON)
            }
        }
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Chat : Screen("chat", "对话", Icons.Filled.Chat)
    object Vm : Screen("vm", "虚拟机", Icons.Filled.Terminal)
    object Settings : Screen("settings", "设置", Icons.Filled.Settings)
    // 非底部栏页面（通过 ChatScreen 内部跳转进入）
    object VoiceChat : Screen("voice_chat", "语音对话", Icons.Filled.Mic)
    object ModelSelection : Screen("model_selection", "模型选择", Icons.Filled.Settings)
}

@Composable
fun JarvisApp() {
    val navController = rememberNavController()
    val wakeEngine = get<WakeEngine>()
    LaunchedEffect(Unit) {
        wakeEngine.wakeEvents.collect {
            navController.navigate(Screen.VoiceChat.route) { launchSingleTop = true }
        }
    }
    val modelScheduler = get<ModelScheduler>()
    val downloadManager = get<ModelDownloadManager>()
    val availableModels by modelScheduler.availableModels.collectAsState()
    val activeModel by modelScheduler.activeModel.collectAsState()
    val downloadStates by downloadManager.downloadStates.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val onboardingPrefs = remember { context.getSharedPreferences(OnboardingGate.PREFS, android.content.Context.MODE_PRIVATE) }
    var showOnboarding by remember {
        mutableStateOf(
            OnboardingGate(
                hasLocalModel = { downloadStates.values.any { it.status == com.jarvis.pineapple.model.DownloadStatus.COMPLETED } }
            ).shouldShowWizard() &&
                !onboardingPrefs.getBoolean(OnboardingGate.KEY_SKIPPED, false) &&
                !onboardingPrefs.getBoolean(OnboardingGate.KEY_CONFIRMED, false)
        )
    }
    val items = listOf(Screen.Chat, Screen.Vm, Screen.Settings)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    if (showOnboarding) {
        ModelOnboardingScreen(
            downloadManager = downloadManager,
            onFinished = { showOnboarding = false }
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Chat.route) {
                ChatScreen(
                    onNavigateToVoiceChat = { navController.navigate(Screen.VoiceChat.route) },
                    onNavigateToModelSelection = { navController.navigate(Screen.ModelSelection.route) },
                    onNavigateToVm = {
                        navController.navigate(Screen.Vm.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(Screen.Vm.route) {
                VirtualMachineScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Settings.route) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.VoiceChat.route) {
                VoiceChatScreen(
                    onBack = { navController.popBackStack() },
                    speechManager = get<SpeechManager>(),
                    voiceWakeManager = get<VoiceWakeManager>()
                )
            }
            composable(Screen.ModelSelection.route) {
                ModelSelectionScreen(
                    onBack = { navController.popBackStack() },
                    availableModels = availableModels,
                    currentModelId = activeModel.info.id,
                    onModelSelected = { modelScheduler.selectModel(it) }
                )
            }
        }
    }
}

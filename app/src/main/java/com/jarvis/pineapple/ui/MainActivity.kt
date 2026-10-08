package com.jarvis.pineapple.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jarvis.pineapple.ui.chat.ChatScreen
import com.jarvis.pineapple.ui.settings.SettingsScreen
import com.jarvis.pineapple.ui.vm.VirtualMachineScreen
import com.jarvis.pineapple.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JarvisTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    JarvisNavHost()
                }
            }
        }
    }
}

@Composable
fun JarvisNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "chat") {
        composable("chat") {
            ChatScreen(
                onOpenVm = { navController.navigate("vm") },
                onOpenSettings = { navController.navigate("settings") },
                onPlayVoice = { /* TTS 播放 */ }
            )
        }
        composable("vm") { VirtualMachineScreen(onBack = { navController.popBackStack() }) }
        composable("settings") { SettingsScreen(onBack = { navController.popBackStack() }) }
    }
}

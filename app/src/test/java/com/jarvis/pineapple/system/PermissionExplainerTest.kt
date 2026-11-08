package com.jarvis.pineapple.system

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionExplainerTest {

    private fun readSource(relativePath: String): String {
        val candidates = listOf(File(relativePath), File("../$relativePath"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("无法定位源文件: $relativePath")
        return file.readText()
    }

    @Test
    fun startupPathDoesNotBatchRequestDangerousPermissions() {
        val main = readSource("app/src/main/java/com/jarvis/pineapple/ui/MainActivity.kt")
        val app = readSource("app/src/main/java/com/jarvis/pineapple/JarvisApp.kt")
        assertFalse("MainActivity 冷启动不得批量 requestPermissions", main.contains("requestPermissions"))
        assertFalse("JarvisApp 冷启动不得批量 requestPermissions", app.contains("requestPermissions"))
        assertFalse(main.contains("ActivityCompat.requestPermissions"))
        assertFalse(app.contains("ActivityCompat.requestPermissions"))
    }

    @Test
    fun rationaleCoversOnDemandDangerousPermissions() {
        assertTrue(PermissionExplainer.rationaleFor(arrayOf(android.Manifest.permission.RECORD_AUDIO)).contains("麦克风"))
        assertTrue(PermissionExplainer.rationaleFor(arrayOf(android.Manifest.permission.CALL_PHONE)).contains("电话"))
        assertTrue(PermissionExplainer.rationaleFor(arrayOf(android.Manifest.permission.SEND_SMS)).contains("短信"))
        assertTrue(PermissionExplainer.rationaleFor(arrayOf(android.Manifest.permission.READ_CALENDAR)).contains("日历"))
        assertTrue(PermissionExplainer.rationaleFor(arrayOf(android.Manifest.permission.CAMERA)).contains("相机"))
        assertTrue(PermissionExplainer.rationaleFor(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)).contains("定位"))
    }

    @Test
    fun cameraAndChatMicUseOnDemandRationale() {
        val camera = readSource("app/src/main/java/com/jarvis/pineapple/ui/camera/CameraCaptureSection.kt")
        val chat = readSource("app/src/main/java/com/jarvis/pineapple/ui/chat/ChatScreen.kt")
        val voice = readSource("app/src/main/java/com/jarvis/pineapple/ui/voice/VoiceChatScreen.kt")
        assertTrue("相机必须走 rememberOnDemandPermission", camera.contains("rememberOnDemandPermission"))
        assertTrue("相机必须请求 CAMERA_PERMISSIONS", camera.contains("CAMERA_PERMISSIONS"))
        assertTrue("聊天麦克风必须走 rememberOnDemandPermission", chat.contains("rememberOnDemandPermission"))
        assertTrue("聊天麦克风必须请求 MICROPHONE_PERMISSIONS", chat.contains("MICROPHONE_PERMISSIONS"))
        assertTrue("语音页拒绝须提示未授予权限", voice.contains("deniedNotice"))
    }
}

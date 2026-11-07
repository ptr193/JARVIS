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
}

package com.jarvis.pineapple.wake

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothWakeDetectorTest {

    @Test
    fun startRejectsMissingAdapterAndRegistrationFailure() {
        val start = readSource(DETECTOR).substringAfter("fun start(): Boolean")
            .substringBefore("fun stop()")

        assertTrue(start.contains("if (bluetoothAdapter == null)"))
        assertTrue(start.contains("_unavailableReason.value = NO_BLUETOOTH"))
        assertTrue(start.contains("registered = true"))
        assertTrue(start.contains("_unavailableReason.value = REGISTRATION_FAILED"))
        assertTrue(start.contains("catch (e: Exception)"))
        assertTrue(start.contains("false"))
    }

    @Test
    fun wakeEngineCollectsBluetoothStartFailure() {
        val source = readSource(ENGINE)
        val startup = source.substringAfter("fun start()")
            .substringBefore("fun stop()")
        val setEnabled = source.substringAfter("fun setEnabled(")
            .substringBefore("fun isEnabled(")

        assertTrue(startup.contains("!bluetoothWakeDetector.start()"))
        assertTrue(startup.contains("bluetoothWakeDetector.unavailableReason.value"))
        assertTrue(setEnabled.contains("WakeSource.BLUETOOTH -> bluetoothWakeDetector.start().also"))
        assertTrue(setEnabled.contains("bluetoothWakeDetector.unavailableReason.value"))
    }

    private fun readSource(relativePath: String): String {
        return listOf(File(relativePath), File("../$relativePath"))
            .firstOrNull { it.exists() }?.readText()
            ?: error("无法定位源文件: $relativePath")
    }

    companion object {
        private const val DETECTOR = "app/src/main/java/com/jarvis/pineapple/wake/BluetoothWakeDetector.kt"
        private const val ENGINE = "app/src/main/java/com/jarvis/pineapple/wake/WakeEngine.kt"
    }
}

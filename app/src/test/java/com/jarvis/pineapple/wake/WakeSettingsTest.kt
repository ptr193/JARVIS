package com.jarvis.pineapple.wake

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeSettingsTest {

    @Test
    fun engineAddsSourceOnlyAfterDetectorStarts() {
        val method = readSource(WAKE_ENGINE).substringAfter("fun setEnabled(")
            .substringBefore("fun isEnabled(")

        assertTrue(method.contains("): Boolean"))
        assertTrue(method.contains("val started = when (source)"))
        assertTrue(method.contains("if (started) enabledSources.add(source) else enabledSources.remove(source)"))
        assertTrue(method.contains("return started"))
    }

    @Test
    fun settingsDoNotPersistFailedWakeChanges() {
        val source = readSource(SETTINGS)
        val update = source.substringAfter("fun updateWakeSource(")
            .substringBefore("SettingSwitch(\"语音唤醒\"")
        val apply = source.substringAfter("private fun applyWakeSource(")
            .substringBefore("private fun saveGeneralPrefs(")

        assertTrue(update.contains("if (!applyWakeSource(context, source, enabled))"))
        assertTrue(update.contains("设置未保存"))
        assertTrue(update.indexOf("return") < update.indexOf("updateState()"))
        assertTrue(update.indexOf("updateState()") < update.indexOf("saveGeneralPrefs("))
        assertTrue(apply.contains("): Boolean"))
        assertTrue(apply.contains("Log.e"))
        assertFalse(apply.contains("runCatching"))
    }

    @Test
    fun startupClearsSavedSourceWhenRestoreFails() {
        val method = readSource(MAIN_ACTIVITY).substringAfter("private fun applySavedWakePreferences()")
            .substringBefore("private fun handleWakeIntent(")

        assertTrue(method.contains("val applied = try"))
        assertTrue(method.contains("if (requested && !applied)"))
        assertTrue(method.contains("putBoolean(key, false)"))
        assertFalse(method.contains("runCatching"))
    }

    @Test
    fun voiceStartVerifiesRecordingBeforeReportingSuccess() {
        val source = readSource(VOICE_DETECTOR)
        val start = source.substringAfter("fun start(): Boolean")
            .substringBefore("private fun prepareAudioRecording()")
        val prepare = source.substringAfter("private fun prepareAudioRecording()")
            .substringBefore("private fun failStart(")

        assertTrue(start.contains("!prepareAudioRecording()"))
        assertTrue(start.indexOf("!prepareAudioRecording()") < start.indexOf("_listening.value = true"))
        assertTrue(start.indexOf("!prepareAudioRecording()") < start.lastIndexOf("return true"))
        assertTrue(start.contains("SpeechRecognizer.isRecognitionAvailable(context)"))
        assertTrue(prepare.contains("audioRecord.startRecording()"))
        assertTrue(prepare.contains("audioRecord.recordingState != android.media.AudioRecord.RECORDSTATE_RECORDING"))
        assertTrue(prepare.contains("return failStart(AUDIO_UNAVAILABLE"))
    }

    private fun readSource(relativePath: String): String {
        return listOf(File(relativePath), File("../$relativePath"))
            .firstOrNull { it.exists() }?.readText()
            ?: error("无法定位源文件: $relativePath")
    }

    companion object {
        private const val WAKE_ENGINE = "app/src/main/java/com/jarvis/pineapple/wake/WakeEngine.kt"
        private const val SETTINGS = "app/src/main/java/com/jarvis/pineapple/ui/settings/SettingsScreen.kt"
        private const val MAIN_ACTIVITY = "app/src/main/java/com/jarvis/pineapple/ui/MainActivity.kt"
        private const val VOICE_DETECTOR = "app/src/main/java/com/jarvis/pineapple/wake/VoiceWakeDetector.kt"
    }
}

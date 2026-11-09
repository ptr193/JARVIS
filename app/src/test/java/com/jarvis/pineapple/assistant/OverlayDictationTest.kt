package com.jarvis.pineapple.assistant

import android.speech.SpeechRecognizer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayDictationTest {

    @Test
    fun asrErrorsAreChineseAndNeverFakeSuccess() {
        assertEquals("未授予麦克风权限", JarvisVoiceInteractionSession.asrErrorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals("未识别到语音", JarvisVoiceInteractionSession.asrErrorMessage(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals("语音输入超时", JarvisVoiceInteractionSession.asrErrorMessage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals("语音识别失败", JarvisVoiceInteractionSession.asrErrorMessage(99))
    }

    @Test
    fun overlaySessionStartsRecognizerWithoutLaunchingMainUi() {
        val src = readSource("app/src/main/java/com/jarvis/pineapple/assistant/JarvisVoiceInteractionSessionService.kt")
        assertTrue(src.contains("SpeechRecognizer.createSpeechRecognizer"))
        assertTrue(src.contains("onRmsChanged"))
        assertTrue(src.contains("assist_mic_denied") || src.contains("未授予麦克风权限"))
        assertTrue(!src.contains("startVoiceActivity"))
        assertTrue(!src.contains("MainActivity"))
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(File(relativePath), File("../$relativePath"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("无法定位源文件: $relativePath")
        return file.readText()
    }
}

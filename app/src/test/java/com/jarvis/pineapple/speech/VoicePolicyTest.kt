package com.jarvis.pineapple.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePolicyTest {

    @Test
    fun defaultVoiceIsIronManChineseMale() {
        assertEquals("jarvis_zh_cn", VoicePolicy.DEFAULT_ID)
        assertEquals("male", VoicePolicy.DEFAULT_GENDER)
        assertTrue(VoicePolicy.DEFAULT_DESCRIPTION.contains("钢铁侠"))
        val pack = VoicePack(
            id = VoicePolicy.DEFAULT_ID,
            name = "JARVIS",
            language = "zh-CN",
            gender = VoicePolicy.DEFAULT_GENDER,
            isDefault = true
        )
        assertEquals("male", pack.gender)
    }

    @Test
    fun missingSourceFallsBackToSystemChineseTts() {
        val resolved = VoicePolicy.resolveActive(preferred = null, downloaded = emptyList())
        assertTrue(resolved.usingFallback)
        assertEquals(VoicePolicy.FALLBACK_ID, resolved.voice.id)
        assertEquals("zh-CN", resolved.voice.language)
        assertEquals("male", resolved.voice.gender)
        assertEquals("声线源不可达，已降级系统中文 TTS", resolved.notice)
    }

    @Test
    fun downloadedDefaultIsPreferred() {
        val ready = VoicePack(
            id = VoicePolicy.DEFAULT_ID,
            name = "JARVIS",
            language = "zh-CN",
            gender = "male",
            isDownloaded = true,
            isDefault = true
        )
        val resolved = VoicePolicy.resolveActive(preferred = ready, downloaded = listOf(ready))
        assertFalse(resolved.usingFallback)
        assertEquals(VoicePolicy.DEFAULT_ID, resolved.voice.id)
        assertNull(resolved.notice)
    }

    @Test
    fun voiceConfigUrlStaysBesideModel() {
        assertEquals(
            "https://example.com/voices/zh_CN-chaowen-medium.onnx.json",
            VoicePolicy.configUrl(
                "https://example.com/voices/zh_CN-chaowen-medium.onnx",
                "zh_CN-chaowen-medium.onnx.json"
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            VoicePolicy.configUrl("https://example.com/voice.onnx", "")
        }
    }

    @Test
    fun voiceDownloadRejectsEmptyPlaceholderAndErrorPage() {
        val validConfig = """{"audio":{"sample_rate":22050}}"""
        assertThrows(IllegalArgumentException::class.java) {
            VoicePolicy.validateDownloadedPackage(ByteArray(0), validConfig)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoicePolicy.validateDownloadedPackage(ByteArray(1024), validConfig)
        }
        assertThrows(Exception::class.java) {
            VoicePolicy.validateDownloadedPackage(ByteArray(2048), "<html>404 Not Found</html>")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoicePolicy.validateDownloadedPackage(ByteArray(2048), """{"audio":{}}""")
        }
    }

    @Test
    fun voiceDownloadAcceptsModelWithValidPiperConfig() {
        VoicePolicy.validateDownloadedPackage(
            ByteArray(2048),
            """{"audio":{"sample_rate":22050},"phoneme_id_map":{}}"""
        )
    }
}

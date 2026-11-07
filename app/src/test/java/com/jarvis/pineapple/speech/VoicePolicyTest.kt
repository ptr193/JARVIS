package com.jarvis.pineapple.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}

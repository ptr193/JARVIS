package com.jarvis.pineapple.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationEngineTest {

    @Test
    fun `valid JSON returns decoded translation`() {
        val result = TranslationEngine.parseGenericResponse(
            "hello",
            "en",
            "zh",
            "{\"translated_text\":\"你好\\n世界\"}"
        )

        assertTrue(result.isSuccess)
        assertEquals("你好\n世界", result.translatedText)
        assertNull(result.errorMessage)
    }

    @Test
    fun `invalid or empty responses never fall back to original text`() {
        val invalid = TranslationEngine.parseGenericResponse("hello", "en", "zh", "not json")
        val missing = TranslationEngine.parseGenericResponse("hello", "en", "zh", "{}")

        assertFalse(invalid.isSuccess)
        assertFalse(missing.isSuccess)
        assertEquals("", invalid.translatedText)
        assertEquals("", missing.translatedText)
        assertTrue(invalid.errorMessage!!.contains("translated_text"))
    }

    @Test
    fun `failure has explicit message and no fabricated translation`() {
        val result = TranslationEngine.failure("hello", "en", "zh", "service unavailable")

        assertFalse(result.isSuccess)
        assertEquals("", result.translatedText)
        assertEquals("service unavailable", result.errorMessage)
    }
}

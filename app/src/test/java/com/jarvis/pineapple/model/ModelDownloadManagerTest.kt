package com.jarvis.pineapple.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadManagerTest {

    @Test
    fun defaultModelsHaveOfficialSizesAndChecksums() {
        assertEquals(
            setOf("gemma4_e2b_2_5b", "gemma4_12b", "lfm2_5_1_7b", "qwen3_0_6b"),
            ModelDownloadManager.DEFAULT_MODELS.map { it.id }.toSet()
        )
        ModelDownloadManager.DEFAULT_MODELS.forEach { config ->
            assertTrue("${config.id} 缺少真实大小", config.sizeBytes > 1024)
            assertTrue(
                "${config.id} 缺少 SHA-256",
                config.sha256.matches(Regex("[0-9a-f]{64}"))
            )
        }
    }

    @Test
    fun completedFileMustMatchExactOfficialSize() {
        val config = ModelDownloadManager.DEFAULT_MODELS.first { it.id == "qwen3_0_6b" }
        assertEquals(613_722_264L, config.sizeBytes)
        assertTrue(ModelDownloadManager.isExpectedSize(config, config.sizeBytes))
        assertFalse(ModelDownloadManager.isExpectedSize(config, 0))
        assertFalse(ModelDownloadManager.isExpectedSize(config, 1024))
        assertFalse(ModelDownloadManager.isExpectedSize(config, config.sizeBytes - 1))
        assertFalse(ModelDownloadManager.isExpectedSize(config, config.sizeBytes + 1))
    }

    @Test
    fun placeholderAndTruncatedModelCannotBeMarkedCompleted() {
        val config = ModelDownloadManager.DEFAULT_MODELS.first()
        assertFalse(ModelDownloadManager.isExpectedSize(config, "<html>404</html>".length.toLong()))
        assertFalse(ModelDownloadManager.isExpectedSize(config, config.sizeBytes / 2))
    }
}

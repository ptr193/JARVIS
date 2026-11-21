package com.jarvis.pineapple.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloadManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

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

    @Test
    fun modelDeletionReportsEveryFailedFile() {
        val file = temp.newFile("model.litertlm")
        val nonEmptyDirectory = temp.newFolder("model-directory")
        val child = File(nonEmptyDirectory, "weights").apply { writeText("data") }

        assertEquals(
            listOf(nonEmptyDirectory),
            ModelDownloadManager.failedDeletions(listOf(file, file, nonEmptyDirectory))
        )
        assertFalse(file.exists())
        assertTrue(child.exists())
        assertTrue(
            ModelDownloadManager.failedDeletions(listOf(file, File(temp.root, "missing"))).isEmpty()
        )
    }

    @Test
    fun downloadStateChangesOnlyAfterDeletionSucceeds() {
        val path = "app/src/main/java/com/jarvis/pineapple/model/ModelDownloadManager.kt"
        val source = listOf(File(path), File("../$path")).firstOrNull { it.exists() }?.readText()
            ?: error("无法定位源文件: $path")
        val delete = source.substringAfter("fun deleteModel(modelId: String): Boolean")
            .substringBefore("    /**\n     * 导入本地模型文件")

        assertTrue(delete.contains("if (failedDeletions(candidates).isNotEmpty()) return false"))
        assertTrue(delete.indexOf("return false") < delete.indexOf("updateState("))
        assertTrue(delete.contains("return true"))
    }
}

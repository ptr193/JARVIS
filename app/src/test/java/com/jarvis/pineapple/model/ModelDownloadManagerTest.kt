package com.jarvis.pineapple.model

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
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
        val ids = ModelDownloadManager.DEFAULT_MODELS.map { it.id }
        assertEquals(29, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(
            ids.containsAll(
                setOf(
                    "qwen3_0_6b",
                    "qwen2_5_1_5b",
                    "deepseek_r1_distill_qwen_1_5b",
                    "gemma4_e2b_2_5b",
                    "gemma4_12b",
                    "lfm2_5_1_7b"
                )
            )
        )
        assertEquals(
            5,
            ModelDownloadManager.DEFAULT_MODELS.count { it.isBundled }
        )
        val bundled = ModelDownloadManager.DEFAULT_MODELS.filter { it.isBundled }
        bundled.forEach { config ->
            assertTrue("${config.id} 缺少归档大小", config.archiveBytes > 0)
            assertTrue(
                "${config.id} 归档路径非法",
                config.bundledAsset == ModelDownloadManager.BUNDLE_RECOMMENDED ||
                    config.bundledAsset == ModelDownloadManager.BUNDLE_SECONDARY
            )
        }
        assertEquals(3, bundled.count { it.bundledAsset == ModelDownloadManager.BUNDLE_RECOMMENDED })
        assertEquals(2, bundled.count { it.bundledAsset == ModelDownloadManager.BUNDLE_SECONDARY })
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
            .substringBefore("    /**\n     * 获取下载进度")

        assertTrue(delete.contains("if (failedDeletions(candidates).isNotEmpty()) return false"))
        assertTrue(delete.indexOf("return false") < delete.indexOf("updateState("))
        assertTrue(delete.contains("return true"))
    }

    @Test
    fun bundledModelExtractsAndVerifiesWithoutNetwork() = runBlocking {
        val payload = ByteArray(2048) { (it % 37).toByte() }
        val archive = temp.newFile("tiny.7z")
        SevenZOutputFile(archive).use { output ->
            val entry = SevenZArchiveEntry().apply {
                name = "tiny.litertlm"
                size = payload.size.toLong()
            }
            output.putArchiveEntry(entry)
            output.write(payload)
            output.closeArchiveEntry()
        }
        val requests = AtomicInteger()
        val modelDirectory = temp.newFolder("bundled-models")
        val config = testConfig(payload).copy(
            bundledAsset = "models/tiny.7z",
            archiveBytes = archive.length()
        )
        val manager = ModelDownloadManager(
            modelDirectory,
            HttpClient(MockEngine {
                requests.incrementAndGet()
                respond(ByteArray(0))
            }),
            listOf(config),
            bundledAssetOpener = { path ->
                if (path == "models/tiny.7z") archive.inputStream() else null
            },
            cacheDir = temp.newFolder("bundled-cache")
        )

        assertTrue(manager.installModel("tiny"))
        assertArrayEquals(payload, File(modelDirectory, "tiny.litertlm").readBytes())
        assertEquals(0, requests.get())
        assertEquals(DownloadStatus.COMPLETED, manager.getDownloadProgress("tiny")?.status)
        assertFalse(File(modelDirectory, "tiny.litertlm.extracting").exists())
        assertEquals(File(modelDirectory, "tiny.litertlm").absolutePath, manager.getModelFilePath("tiny"))
    }

    @Test
    fun sharedArchiveExtractsOnlyRequestedEntryAndCopiesOnce() = runBlocking {
        val alphaPayload = ByteArray(2048) { (it % 17).toByte() }
        val betaPayload = ByteArray(4096) { (it % 23).toByte() }
        val archive = temp.newFile("shared.7z")
        SevenZOutputFile(archive).use { output ->
            listOf("alpha.litertlm" to alphaPayload, "beta.litertlm" to betaPayload).forEach { (name, payload) ->
                val entry = SevenZArchiveEntry().apply {
                    this.name = name
                    size = payload.size.toLong()
                }
                output.putArchiveEntry(entry)
                output.write(payload)
                output.closeArchiveEntry()
            }
        }
        val requests = AtomicInteger()
        val openerCalls = AtomicInteger()
        val modelDirectory = temp.newFolder("shared-models")
        val cacheDir = temp.newFolder("shared-cache")
        val alpha = ModelDownloadManager.ModelConfig(
            id = "alpha", name = "Alpha", fileName = "alpha.litertlm",
            downloadUrl = "https://models.test/alpha",
            sha256 = sha256(alphaPayload), sizeBytes = alphaPayload.size.toLong(),
            bundledAsset = "models/shared.7z", archiveBytes = archive.length()
        )
        val beta = alpha.copy(
            id = "beta", name = "Beta", fileName = "beta.litertlm",
            downloadUrl = "https://models.test/beta",
            sha256 = sha256(betaPayload), sizeBytes = betaPayload.size.toLong()
        )
        val manager = ModelDownloadManager(
            modelDirectory,
            HttpClient(MockEngine {
                requests.incrementAndGet()
                respond(ByteArray(0))
            }),
            listOf(alpha, beta),
            bundledAssetOpener = { path ->
                openerCalls.incrementAndGet()
                if (path == "models/shared.7z") archive.inputStream() else null
            },
            cacheDir = cacheDir
        )

        assertTrue(manager.installModel("alpha"))
        assertTrue(manager.installModel("beta"))

        // 同一压缩包内两款模型共享同一份缓存，assets 只读取一次，且全程不发起网络请求。
        assertEquals(1, openerCalls.get())
        assertEquals(0, requests.get())
        assertArrayEquals(alphaPayload, File(modelDirectory, "alpha.litertlm").readBytes())
        assertArrayEquals(betaPayload, File(modelDirectory, "beta.litertlm").readBytes())
        assertFalse(File(modelDirectory, "alpha.litertlm.extracting").exists())
        assertFalse(File(modelDirectory, "beta.litertlm.extracting").exists())
        assertEquals(DownloadStatus.COMPLETED, manager.getDownloadProgress("alpha")?.status)
        assertEquals(DownloadStatus.COMPLETED, manager.getDownloadProgress("beta")?.status)
        // 解压成功后缓存归档保留，供同包其它模型复用。
        assertTrue(File(cacheDir, "models_shared.7z").exists())
    }

    @Test
    fun sameModelConcurrentDownloadsSerializeAndReuseVerifiedFile() = runBlocking {
        val payload = ByteArray(2048) { (it % 31).toByte() }
        val requests = AtomicInteger()
        val engine = MockEngine {
            requests.incrementAndGet()
            delay(50)
            respond(payload, headers = headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        }
        val manager = ModelDownloadManager(
            temp.newFolder("concurrent-models"),
            HttpClient(engine),
            listOf(testConfig(payload))
        )

        val results = listOf(
            async { manager.downloadModel("tiny") },
            async { manager.downloadModel("tiny") }
        ).awaitAll()

        assertTrue(results.all { it })
        assertEquals(1, requests.get())
        assertEquals(DownloadStatus.COMPLETED, manager.getDownloadProgress("tiny")?.status)
        assertFalse(manager.isDownloading.value)
    }

    @Test
    fun cancellationIsVisibleAndReleasesGlobalDownloadingState() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit)
            awaitCancellation()
        }
        val payload = ByteArray(2048) { 1 }
        val manager = ModelDownloadManager(
            temp.newFolder("cancel-models"),
            HttpClient(engine),
            listOf(testConfig(payload))
        )
        val download = async { manager.downloadModel("tiny") }
        withTimeout(5_000) { started.await() }

        assertTrue(manager.cancelDownload("tiny"))
        assertTrue(runCatching { download.await() }.exceptionOrNull() is CancellationException)
        assertEquals(DownloadStatus.CANCELLED, manager.getDownloadProgress("tiny")?.status)
        assertFalse(manager.isDownloading.value)
        assertFalse(manager.cancelDownload("tiny"))
    }

    @Test
    fun storageFailureReturnsWithoutStartingHttpRequest() = runBlocking {
        val payload = ByteArray(2048) { 4 }
        val requests = AtomicInteger()
        val blockedDirectory = temp.newFile("blocked-models")
        HttpClient(MockEngine {
            requests.incrementAndGet()
            respond(payload)
        }).use { client ->
            val manager = ModelDownloadManager(
                blockedDirectory,
                client,
                listOf(testConfig(payload))
            )

            assertFalse(withTimeout(5_000) { manager.downloadModel("tiny") })
            assertEquals(0, requests.get())
            assertEquals(DownloadStatus.FAILED, manager.getDownloadProgress("tiny")?.status)
            assertFalse(manager.isDownloading.value)
        }
    }

    @Test
    fun restoredFileWithMatchingSizeButWrongHashIsNotCompleted() = runBlocking {
        val payload = ByteArray(2048) { 2 }
        val directory = temp.newFolder("restored-models")
        File(directory, "tiny.litertlm").writeBytes(payload)
        val wrongHash = sha256(ByteArray(2048) { 3 })
        val manager = ModelDownloadManager(
            directory,
            HttpClient(MockEngine { respond(ByteArray(0)) }),
            listOf(testConfig(payload).copy(sha256 = wrongHash))
        )

        withTimeout(5_000) {
            while (manager.getDownloadProgress("tiny")?.status == DownloadStatus.VERIFYING) delay(10)
        }
        assertEquals(DownloadStatus.FAILED, manager.getDownloadProgress("tiny")?.status)
        assertEquals(null, manager.getModelFilePath("tiny"))
    }

    private fun testConfig(payload: ByteArray) = ModelDownloadManager.ModelConfig(
        id = "tiny",
        name = "Tiny test model",
        fileName = "tiny.litertlm",
        downloadUrl = "https://models.test/tiny",
        sha256 = sha256(payload),
        sizeBytes = payload.size.toLong()
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

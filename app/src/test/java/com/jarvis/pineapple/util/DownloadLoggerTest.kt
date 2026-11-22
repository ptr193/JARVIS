package com.jarvis.pineapple.util

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadLoggerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun appendVerifiedIncreasesFileSizeAndReturnsTrue() {
        val dir = temp.newFolder("logs")
        val file = File(dir, "jarvis_app_20261122.log")

        assertTrue(DownloadLogger.appendVerified(dir, file.name, "first\n"))
        assertTrue(DownloadLogger.appendVerified(dir, file.name, "second\n"))

        assertEquals("first\nsecond\n", file.readText())
    }

    @Test
    fun writeVerifiedTextValidatesExactUtf8Length() {
        val file = File(temp.root, "verified.txt")

        assertTrue(DownloadLogger.writeVerifiedText(file, "中文日志"))
        assertEquals("中文日志".toByteArray(Charsets.UTF_8).size.toLong(), file.length())
    }

    @Test
    fun runtimeLogFilesOnlyIncludesManagedRuntimeLogs() {
        val dir = temp.newFolder("logs")
        File(dir, "jarvis_app_20261122.log").writeText("app")
        File(dir, "jarvis_startup_20261122_120000.txt").writeText("startup")
        File(dir, "data_log.jsonl").writeText("{}")
        File(dir, "jarvis_log_20261122_120000_000.txt").writeText("export")
        File(dir, "memory_export.json").writeText("memory")

        val names = DownloadLogger.runtimeLogFiles(dir).map { it.name }

        assertEquals(
            listOf("data_log.jsonl", "jarvis_app_20261122.log", "jarvis_startup_20261122_120000.txt"),
            names
        )
    }

    @Test
    fun exportLogsToFileIncludesAppAndCrashRecords() {
        val dir = temp.newFolder("logs")
        val app = File(dir, "jarvis_app_20261122.log").apply { writeText("app line\n") }
        val crash = File(temp.newFolder("crash"), "crash_1.txt").apply { writeText("crash line") }
        val output = File(dir, "jarvis_log_export.txt")

        val result = DownloadLogger.exportLogsToFile(listOf(app), listOf(crash), output)

        val text = result.readText()
        assertTrue(text.startsWith("JARVIS 日志导出"))
        assertTrue(text.contains("########## jarvis_app_20261122.log ##########"))
        assertTrue(text.contains("########## crash_1.txt ##########"))
        assertTrue(result.length() > 0L)
    }

    @Test
    fun clearFilesReportsPartialDeletionFailure() {
        val deleted = temp.newFile("jarvis_app_20261122.log")
        val directory = temp.newFolder("jarvis_app_blocked.log")
        File(directory, "remaining.txt").writeText("still here")

        val error = assertThrows(IOException::class.java) {
            DownloadLogger.clearFiles(listOf(deleted, directory))
        }

        assertTrue(error.message.orEmpty().contains("1 个应用日志文件删除失败"))
        assertTrue(directory.exists())
    }

    @Test
    fun readVerifiedFilesDoesNotHideUnreadableFile() {
        val missing = File(temp.root, "jarvis_app_missing.log")

        assertThrows(IOException::class.java) {
            DownloadLogger.readVerifiedFiles(listOf(missing))
        }
    }
}

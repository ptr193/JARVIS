package com.jarvis.pineapple.util

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashRecorderTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun readRecordsReturnsActualFileContents() {
        val first = temp.newFile("crash_2.txt").apply { writeText("second") }
        val second = temp.newFile("crash_1.txt").apply { writeText("first") }

        val text = CrashRecorder.readRecords(listOf(first, second))

        assertEquals(
            "########## crash_2.txt ##########\nsecond\n\n" +
                "########## crash_1.txt ##########\nfirst",
            text
        )
    }

    @Test
    fun readRecordsDoesNotHideMissingFileFailure() {
        val missing = File(temp.root, "crash_missing.txt")

        assertThrows(FileNotFoundException::class.java) {
            CrashRecorder.readRecords(listOf(missing))
        }
    }

    @Test
    fun clearReportsPartialDeletionFailure() {
        val deleted = temp.newFile("crash_old.txt")
        val nonEmptyDirectory = temp.newFolder("crash_blocked")
        File(nonEmptyDirectory, "remaining.txt").writeText("still here")

        val error = assertThrows(IOException::class.java) {
            CrashRecorder.clearRecords(listOf(deleted, nonEmptyDirectory))
        }

        assertTrue(error.message.orEmpty().contains("1 个崩溃文件删除失败"))
        assertTrue(nonEmptyDirectory.exists())
    }

    @Test
    fun clearReturnsCountOnlyAfterDeletingEveryRecord() {
        val files = listOf(temp.newFile("crash_1.txt"), temp.newFile("crash_2.txt"))

        assertEquals(2, CrashRecorder.clearRecords(files))
        assertTrue(files.none { it.exists() })
    }
}

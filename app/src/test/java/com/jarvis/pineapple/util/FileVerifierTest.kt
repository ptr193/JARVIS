package com.jarvis.pineapple.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileVerifierTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun writeVerifiedTextValidatesExactUtf8Length() {
        val file = File(temp.root, "verified.txt")

        assertTrue(FileVerifier.writeVerifiedText(file, "中文日志"))
        assertEquals("中文日志".toByteArray(Charsets.UTF_8).size.toLong(), file.length())
        assertEquals("中文日志", file.readText())
    }

    @Test
    fun writeVerifiedTextReturnsFalseWhenTargetIsDirectory() {
        val directory = temp.newFolder("blocked.txt")

        assertFalse(FileVerifier.writeVerifiedText(directory, "cannot overwrite directory"))
    }

    @Test
    fun writeVerifiedTextReturnsFalseWhenParentCannotBeCreated() {
        // 父路径是一个已存在的普通文件，mkdirs 必失败
        val parentAsFile = temp.newFile("not_a_dir")
        val file = File(parentAsFile, "child.txt")

        assertFalse(FileVerifier.writeVerifiedText(file, "x"))
    }

    @Test
    fun writeVerifiedTextCreatesMissingParentDirectory() {
        val file = File(temp.root, "nested/deep/verified.txt")

        assertTrue(FileVerifier.writeVerifiedText(file, "ok"))
        assertTrue(file.exists())
        assertEquals("ok", file.readText())
    }
}

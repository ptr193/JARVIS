package com.jarvis.pineapple.vm

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TarExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `extract rejects parent traversal`() {
        val archive = archiveWith("entry.tar") {
            addFile("../outside.txt", "blocked")
        }
        val target = tmp.newFolder("target")

        expectSecurityFailure { TarExtractor.extract(archive, target) }
        assertFalse(File(tmp.root, "outside.txt").exists())
    }

    @Test
    fun `extract rejects sibling whose name only shares target prefix`() {
        val archive = archiveWith("entry.tar") {
            addFile("../target-evil/pwned.txt", "blocked")
        }
        val target = tmp.newFolder("target")

        expectSecurityFailure { TarExtractor.extract(archive, target) }
        assertFalse(File(tmp.root, "target-evil/pwned.txt").exists())
    }

    @Test
    fun `extract rejects hard link source outside target`() {
        val archive = archiveWith("link.tar") {
            val entry = TarArchiveEntry("linked.txt", TarConstants.LF_LINK)
            entry.setLinkName("../outside.txt")
            putArchiveEntry(entry)
            closeArchiveEntry()
        }
        val target = tmp.newFolder("hard-link-target")

        expectSecurityFailure { TarExtractor.extract(archive, target) }
        assertFalse(File(target, "linked.txt").exists())
    }

    @Test
    fun `archive round trip restores regular files`() {
        val source = tmp.newFolder("source")
        File(source, "bin").mkdirs()
        File(source, "bin/run.sh").writeText("#!/bin/sh\necho ok\n")
        File(source, "data.txt").writeText("payload")
        val archive = File(tmp.root, "snapshot.tar.gz")
        val restored = tmp.newFolder("restored")

        TarExtractor.createArchive(source, archive)
        TarExtractor.extract(archive, restored)

        assertEquals("payload", File(restored, "data.txt").readText())
        assertEquals("#!/bin/sh\necho ok\n", File(restored, "bin/run.sh").readText())
        assertTrue(archive.length() > 0)
    }

    private fun archiveWith(name: String, write: TarArchiveOutputStream.() -> Unit): File {
        val archive = File(tmp.root, name)
        TarArchiveOutputStream(archive.outputStream()).use { it.write() }
        return archive
    }

    private fun TarArchiveOutputStream.addFile(path: String, content: String) {
        val bytes = content.toByteArray()
        val entry = TarArchiveEntry(path)
        entry.size = bytes.size.toLong()
        putArchiveEntry(entry)
        write(bytes)
        closeArchiveEntry()
    }

    private fun expectSecurityFailure(block: () -> Unit) {
        try {
            block()
            fail("expected SecurityException")
        } catch (_: SecurityException) {
        }
    }
}

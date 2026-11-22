package com.jarvis.pineapple.security

import com.jarvis.pineapple.chat.RootRunResult
import com.jarvis.pineapple.system.HostRootManager
import com.jarvis.pineapple.vm.VmAuditLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class AuditLogStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `concurrent appends stay serialized and capped at 500`() {
        val file = tmp.newFile("audit.log")
        val store = AuditLogStore(file)
        val executor = Executors.newFixedThreadPool(8)
        val failures = try {
            val jobs = (1..800).map { index ->
                Callable { if (store.append("event=$index")) null else index }
            }
            executor.invokeAll(jobs).mapNotNull { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertTrue(failures.isEmpty())
        assertEquals(500, file.readLines().size)
        assertEquals(500, store.snapshot().size)
        assertEquals(500, file.readLines().toSet().size)
        assertFalse(File(tmp.root, "audit.log.tmp").exists())
    }

    @Test
    fun `sanitizer removes common credentials and line injection`() {
        val sanitized = AuditSanitizer.sanitize(
            "curl -H 'Authorization: Bearer bearer-secret' " +
                "--password 'two words' token=token-secret\r\n" +
                "https://alice:url-secret@example.test"
        )

        assertFalse(sanitized.contains("bearer-secret"))
        assertFalse(sanitized.contains("two words"))
        assertFalse(sanitized.contains("token-secret"))
        assertFalse(sanitized.contains("url-secret"))
        assertFalse(sanitized.contains('\r') || sanitized.contains('\n'))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun `write failure is visible and does not replace in memory entries`() {
        val file = tmp.newFile("audit.log")
        val store = AuditLogStore(file)
        assertTrue(store.append("first"))
        assertTrue(file.delete())
        assertTrue(file.mkdir())

        assertFalse(store.append("second"))

        assertEquals(listOf("first"), store.snapshot())
        assertTrue(store.lastError.orEmpty().contains("写入失败"))
    }

    @Test
    fun `vm audit redacts command and reports persistence failure`() {
        val log = VmAuditLog(tmp.newFile("vm.log"))
        assertTrue(log.record("vm\ninjected", "root", "echo ok --token secret", 7))

        val entry = log.recent().single()
        assertTrue(entry.contains("exit=7"))
        assertFalse(entry.contains("secret"))
        assertFalse(log.entries.value.any { it.contains('\n') })

        val failed = VmAuditLog(tmp.newFolder("vm-blocked"))
        assertFalse(failed.record("vm", "root", "id", 0))
        assertTrue(failed.lastError.orEmpty().contains("写入失败"))
    }

    @Test
    fun `root audit failure preserves real exit code and appends warning`() = runBlocking {
        val manager = HostRootManager(
            auditStore = AuditLogStore(tmp.newFolder("root-blocked")),
            allowedProvider = { true },
            allowedUpdater = {},
            suRunner = { _, _ -> RootRunResult("real-output", 23) }
        )

        val result = manager.execute("id")

        assertEquals(23, result.exitCode)
        assertTrue(result.output.startsWith("real-output"))
        assertTrue(result.output.contains("警告"))
        assertTrue(manager.auditError.orEmpty().contains("写入失败"))
    }

    @Test
    fun `successful root audit stores redacted command without changing result`() = runBlocking {
        val file = tmp.newFile("root.log")
        val manager = HostRootManager(
            auditStore = AuditLogStore(file),
            allowedProvider = { true },
            allowedUpdater = {},
            suRunner = { _, _ -> RootRunResult("ok", 0) }
        )

        val result = manager.execute("service restart --api-key raw-secret")

        assertEquals(RootRunResult("ok", 0), result)
        assertEquals(1, manager.auditEntries().size)
        assertFalse(file.readText().contains("raw-secret"))
        assertTrue(file.readText().contains("[REDACTED]"))
    }
}

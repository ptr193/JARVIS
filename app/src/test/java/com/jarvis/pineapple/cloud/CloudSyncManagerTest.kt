package com.jarvis.pineapple.cloud

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class CloudSyncManagerTest {

    @Test
    fun `login persists token privately and device discovery sends bearer header`() = runBlocking {
        val store = FakeSessionStore()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/login" -> respondJson("""{"success":true,"token":"token-123"}""")
                "/api/devices" -> {
                    assertEquals("Bearer token-123", request.headers[HttpHeaders.Authorization])
                    respondJson("""{"devices":[{"id":"phone-2","name":"Phone","type":"phone","online":true}]}""")
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val manager = CloudSyncManager(BASE_URL, engine, store)

        assertTrue(manager.login("alice", "password"))
        assertEquals(StoredCloudSession("alice", "token-123"), store.saved)
        assertTrue(manager.isLoggedIn())
        assertEquals("alice", manager.accountName.value)

        val devices = manager.discoverDevices()
        assertEquals(1, devices?.size)
        assertEquals("phone-2", devices?.single()?.deviceId)
        assertEquals(devices, manager.syncStatus.value.connectedDevices)
        assertNull(manager.lastError.value)
    }

    @Test
    fun `unconfigured cloud and missing login are distinct visible failures`() = runBlocking {
        val disabledEngine = MockEngine { error("request must not be sent") }
        val disabled = CloudSyncManager("", disabledEngine, FakeSessionStore())
        assertFalse(disabled.login("alice", "password"))
        assertTrue(disabled.lastError.value.orEmpty().contains("云端未配置"))
        assertTrue(disabledEngine.requestHistory.isEmpty())

        val engine = MockEngine { error("request must not be sent") }
        val manager = CloudSyncManager(BASE_URL, engine, FakeSessionStore())
        assertNull(manager.discoverDevices())
        assertTrue(manager.lastError.value.orEmpty().contains("尚未登录"))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `http failure does not save an authenticated session`() = runBlocking {
        val store = FakeSessionStore()
        val manager = CloudSyncManager(
            BASE_URL,
            MockEngine { respond("denied", HttpStatusCode.Unauthorized) },
            store
        )

        assertFalse(manager.login("alice", "bad-password"))
        assertTrue(manager.lastError.value.orEmpty().contains("HTTP 401"))
        assertFalse(manager.isLoggedIn())
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun `secure storage failure rejects login state despite successful server response`() = runBlocking {
        val store = FakeSessionStore(saveResult = false)
        val manager = CloudSyncManager(
            BASE_URL,
            MockEngine { respondJson("""{"success":true,"token":"token-123"}""") },
            store
        )

        assertFalse(manager.login("alice", "password"))
        assertFalse(manager.isLoggedIn())
        assertTrue(manager.lastError.value.orEmpty().contains("安全保存失败"))
    }

    @Test
    fun `network and timeout failures are distinguished`() = runBlocking {
        val offline = CloudSyncManager(
            BASE_URL,
            MockEngine { throw IOException("offline") },
            FakeSessionStore()
        )
        assertFalse(offline.login("alice", "password"))
        assertTrue(offline.lastError.value.orEmpty().contains("网络连接失败"))

        val timeout = CloudSyncManager(
            BASE_URL,
            MockEngine { throw SocketTimeoutException("timeout") },
            FakeSessionStore()
        )
        assertFalse(timeout.login("alice", "password"))
        assertTrue(timeout.lastError.value.orEmpty().contains("超时"))
    }

    @Test
    fun `restored session survives manager recreation and logout failure is visible`() = runBlocking {
        val store = FakeSessionStore(
            initial = StoredCloudSession("alice", "saved-token"),
            clearResult = false
        )
        val manager = CloudSyncManager(BASE_URL, MockEngine { respond("unused") }, store)

        assertTrue(manager.isLoggedIn())
        assertEquals("alice", manager.accountName.value)
        manager.logout()

        assertFalse(manager.isLoggedIn())
        assertNull(manager.accountName.value)
        assertTrue(manager.lastError.value.orEmpty().contains("清理失败"))
    }

    @Test
    fun `sync failure resets syncing state without advancing last sync time`() = runBlocking {
        val manager = CloudSyncManager(
            BASE_URL,
            MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) },
            FakeSessionStore(initial = StoredCloudSession("alice", "token"))
        )

        assertFalse(manager.syncSettings("{}"))
        assertFalse(manager.syncStatus.value.isSyncing)
        assertEquals(0, manager.syncStatus.value.lastSyncTime)
        assertTrue(manager.lastError.value.orEmpty().contains("HTTP 503"))
    }

    @Test
    fun `cancellation is propagated and does not become a fake login failure`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val manager = CloudSyncManager(
            BASE_URL,
            MockEngine {
                started.complete(Unit)
                awaitCancellation()
            },
            FakeSessionStore()
        )
        val job = launch { manager.login("alice", "password") }
        started.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertNull(manager.lastError.value)
    }

    private class FakeSessionStore(
        private val initial: StoredCloudSession? = null,
        private val saveResult: Boolean = true,
        private val clearResult: Boolean = true
    ) : CloudSessionStore {
        var saved: StoredCloudSession? = initial
        var saveCalls = 0
        override var lastError: String? = null

        override fun load(): StoredCloudSession? = saved

        override fun save(account: String, token: String): Boolean {
            saveCalls++
            if (saveResult) {
                saved = StoredCloudSession(account, token)
                lastError = null
            } else {
                lastError = "安全保存失败"
            }
            return saveResult
        }

        override fun clear(): Boolean {
            if (clearResult) saved = null
            lastError = if (clearResult) null else "清理失败"
            return clearResult
        }
    }

    private companion object {
        const val BASE_URL = "https://cloud.example.test"

        fun MockRequestHandleScope.respondJson(body: String) = respond(
            body,
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json")
        )
    }
}

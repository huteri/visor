package com.macedonga.visor.core.assistant

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Runs production post -> request, not a substitute transport callback. */
class BrainHttpLeaseTest {
    private class BlockedConnection : HttpURLConnection(URL("http://unused.invalid")) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val bytes = object : ByteArrayOutputStream() {
            override fun close() { super.close(); finished.countDown() }
        }
        override fun getOutputStream(): java.io.OutputStream {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Stream acquisition timed out" }
            return bytes
        }
        override fun disconnect() = Unit
        override fun connect() = Unit
        override fun usingProxy() = false
    }

    private suspend fun post(brain: Brain): Any? = suspendCoroutineUninterceptedOrReturn { continuation ->
        Brain::class.java.getDeclaredMethod("post", String::class.java, Map::class.java, JSONObject::class.java, kotlin.coroutines.Continuation::class.java)
            .apply { isAccessible = true }
            .invoke(brain, "visor-lease-test://request", emptyMap<String, String>(), JSONObject().put("image", "private-image"), continuation)
    }

    private fun blockedWrite(cancel: Boolean) = runBlocking {
        val http = BlockedConnection()
        connection = http
        val allowed = AtomicBoolean(true)
        val owner = SupervisorJob()
        val task = CoroutineScope(owner + Dispatchers.Default).async(LiveImageLease { check(allowed.get()) { "Image revoked" } }) {
            post(Brain({ AssistantConfig() }))
        }
        try {
            assertTrue("Production request must enter getOutputStream", http.entered.await(5, TimeUnit.SECONDS))
            if (cancel) task.cancel() else allowed.set(false)
            http.release.countDown()
            try { task.await(); fail("Revoked request must fail") } catch (_: IllegalStateException) { } catch (_: CancellationException) { }
            assertTrue("Production request must finish", http.finished.await(5, TimeUnit.SECONDS))
            assertEquals("No image bytes may escape after blocked stream acquisition", 0, http.bytes.size())
        } finally {
            http.release.countDown()
            owner.cancel()
        }
    }

    @Test fun revokedLeaseDuringOutputStreamAcquisitionWritesZeroBytes() = blockedWrite(false)
    @Test fun cancelledTurnDuringOutputStreamAcquisitionWritesZeroBytes() = blockedWrite(true)

    companion object {
        @Volatile private var connection: URLConnection? = null
        init {
            TestHttpConnections.handlers["request"] = { checkNotNull(connection) }
        }
    }
}

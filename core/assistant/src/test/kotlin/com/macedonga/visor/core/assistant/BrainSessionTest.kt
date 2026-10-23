package com.macedonga.visor.core.assistant

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrainSessionTest {
    @Test fun cancelledLateHeaderCannotReplaceNewBootstrap() = cancelledHeaderScenario(true)
    @Test fun cancelledHeaderCannotPublishWithoutReplacement() = cancelledHeaderScenario(false)

    private fun cancelledHeaderScenario(replacement: Boolean) = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val consumed = java.util.concurrent.CountDownLatch(1)
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val posts = java.util.concurrent.atomic.AtomicInteger()
        val host = java.util.UUID.randomUUID().toString()
        val dir = Files.createTempDirectory("cancelled-header").toFile()
        connections[host] = { url -> object : java.net.HttpURLConnection(url) {
            private var first = false
            override fun connect() = Unit
            override fun disconnect() = Unit // A blocked JDK read may outlive asynchronous disconnect.
            override fun usingProxy() = false
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode(): Int {
                if (url.path == "/chat/completions") {
                    requests += getRequestProperty("X-Hermes-Session-Id") ?: "bootstrap"
                    first = posts.incrementAndGet() == 1
                    if (first) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                }
                return 200
            }
            override fun getHeaderField(name: String): String? =
                if (name == "X-Hermes-Session-Id") if (first) "cancelled" else "new-session" else null
            override fun getInputStream(): java.io.InputStream {
                if (first) consumed.countDown()
                return (if (url.path == "/capabilities")
                    """{"features":{"session_continuity_header":"X-Hermes-Session-Id"}}"""
                else """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""").byteInputStream()
            }
        } }
        try {
            val diary = Diary(dir)
            val config = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, apiKey = "key", baseUrl = "visor-test://$host")
            suspend fun answer(text: String) = withContext(ConversationContext(diary, "c")) { Brain({ config }).answer(text, Locale.ENGLISH) }
            val first = async(kotlinx.coroutines.Dispatchers.IO) { answer("A") }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            first.cancel(); first.join()
            if (replacement) answer("B")
            release.countDown()
            assertTrue("late header reached worker", consumed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val file = java.io.File(dir, "sessions.json")
            if (replacement) {
                assertEquals("new-session", JSONObject(file.readText()).let { it.getString(it.keys().next()) })
                answer("C")
                assertEquals(listOf("bootstrap", "bootstrap", "new-session"), requests.toList())
            } else assertFalse("cancelled request must not publish session", file.exists())
        } finally { release.countDown(); connections.remove(host); dir.deleteRecursively() }
    }

    companion object {
        private val connections = TestHttpConnections.handlers
    }

    @Test fun deletedConversationRejectsDelayedSessionHeader() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/capabilities") { e ->
            val data = """{"features":{"session_continuity_header":"X-Hermes-Session-Id"}}""".toByteArray()
            e.sendResponseHeaders(200, data.size.toLong()); e.responseBody.use { it.write(data) }
        }
        server.createContext("/chat/completions") { e ->
            e.requestBody.readBytes(); entered.countDown(); release.await()
            e.responseHeaders.set("X-Hermes-Session-Id", "deleted-transcript")
            val data = """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""".toByteArray()
            e.sendResponseHeaders(200, data.size.toLong()); e.responseBody.use { it.write(data) }
        }
        server.start()
        val dir = Files.createTempDirectory("delete-session").toFile()
        try {
            val diary = Diary(dir)
            diary.record(TurnRecord("deleted", 1, "secret", conversation = "c"))
            val context = ConversationContext(diary, "c")
            val config = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, apiKey = "key", baseUrl = "http://127.0.0.1:${server.address.port}")
            val pending = async(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { withContext(context) { Brain({ config }).answer("first", Locale.ENGLISH) } }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            diary.delete(setOf("deleted")); release.countDown()
            assertTrue("deleted capture must fail", pending.await().isFailure)
            assertFalse(java.io.File(dir, "sessions.json").exists())
            val stale = runCatching { withContext(context) { Brain({ config }).answer("stale continuation", Locale.ENGLISH) } }
            assertTrue(stale.isFailure)
        } finally { release.countDown(); server.stop(0); dir.deleteRecursively() }
    }

    @Test fun concurrentBootstrapContinuesFirstTranscript() = runBlocking {
        val firstEntered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/capabilities") { e ->
            val data = """{"features":{"session_continuity_header":"X-Hermes-Session-Id"}}""".toByteArray()
            e.sendResponseHeaders(200, data.size.toLong()); e.responseBody.use { it.write(data) }
        }
        server.createContext("/api/sessions/first") { e -> e.sendResponseHeaders(200, -1); e.close() }
        server.createContext("/chat/completions") { e ->
            e.requestBody.readBytes()
            requests += e.requestHeaders.getFirst("X-Hermes-Session-Id") ?: "bootstrap"
            if (requests.size == 1) { firstEntered.countDown(); release.await() }
            e.responseHeaders.set("X-Hermes-Session-Id", "first")
            val data = """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""".toByteArray()
            e.sendResponseHeaders(200, data.size.toLong()); e.responseBody.use { it.write(data) }
        }
        server.start()
        val dir = Files.createTempDirectory("concurrent-session").toFile()
        try {
            val diary = Diary(dir)
            val config = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, apiKey = "key", baseUrl = "http://127.0.0.1:${server.address.port}")
            val first = async(kotlinx.coroutines.Dispatchers.IO) { withContext(ConversationContext(diary, "c")) { Brain({ config }).answer("first", Locale.ENGLISH) } }
            assertTrue(firstEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val second = async(kotlinx.coroutines.Dispatchers.IO) { withContext(ConversationContext(diary, "c")) { Brain({ config }).answer("second", Locale.ENGLISH) } }
            kotlinx.coroutines.delay(300)
            release.countDown(); first.await(); second.await()
            assertEquals(listOf("bootstrap", "first"), requests.toList())
        } finally { release.countDown(); server.stop(0); executor.shutdownNow(); dir.deleteRecursively() }
    }

    @Test fun conversationLockOrdersHistoryAndReleasesOnCancellation() = runBlocking {
        val dir = Files.createTempDirectory("lock-session").toFile()
        try {
            val diary = Diary(dir)
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val history = mutableListOf<String>()
            val first = async { ConversationContext(diary, "c").serialized {
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            } }
            entered.await()
            val second = async { ConversationContext(diary, "c").serialized { history += "second" } }
            val different = async { ConversationContext(diary, "other").serialized { "other" } }
            assertEquals("other", kotlinx.coroutines.withTimeout(1000) { different.await() })
            first.cancel(); first.join()
            kotlinx.coroutines.withTimeout(1000) { second.await() }
            ConversationContext(diary, "c").serialized { assertEquals(listOf("second"), history); history += "third" }
            assertEquals(listOf("second", "third"), history)
        } finally { dir.deleteRecursively() }
    }

    @Test fun serverToolSuccessAndLostResponseCannotBeRetried() = runBlocking {
        for (lost in listOf(false, true)) {
            val posts = java.util.concurrent.atomic.AtomicInteger()
            val toolEffects = java.util.concurrent.atomic.AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/capabilities") { e ->
                val data = """{"features":{"session_continuity_header":"X-Hermes-Session-Id"}}""".toByteArray()
                e.sendResponseHeaders(200, data.size.toLong()); e.responseBody.use { it.write(data) }
            }
            server.createContext("/chat/completions") { e ->
                e.requestBody.readBytes(); posts.incrementAndGet(); toolEffects.incrementAndGet()
                e.responseHeaders.set("X-Hermes-Session-Id", "tool-session")
                val data = (if (lost) "broken response after tool" else """{"choices":[{"message":{"content":"tool done"},"finish_reason":"stop"}]}""").toByteArray()
                e.sendResponseHeaders(200, data.size.toLong()); e.responseBody.use { it.write(data) }
            }
            server.start()
            val dir = Files.createTempDirectory("retry-tool").toFile()
            try {
                val diary = Diary(dir)
                val config = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, apiKey = "key", baseUrl = "http://127.0.0.1:${server.address.port}")
                val brain = Brain({ config })
                val initial = runCatching { withContext(ConversationContext(diary, "c")) { brain.answer("run tool", Locale.ENGLISH) } }
                assertEquals(!lost, initial.isSuccess)
                val retry = runCatching { withContext(ConversationContext(diary, "c", replay = true)) { brain.answer("run tool", Locale.ENGLISH) } }
                assertTrue(retry.exceptionOrNull()!!.message!!.contains("Retry/Again"))
                assertEquals(1, posts.get()); assertEquals(1, toolEffects.get())
            } finally { server.stop(0); dir.deleteRecursively() }
        }
    }

    @Test fun sessionMetadataHasBoundedRetention() {
        val dir = Files.createTempDirectory("bounded-session").toFile()
        try {
            val diary = Diary(dir)
            repeat(140) { diary.keepSession("scope-$it", "id-$it") }
            val json = JSONObject(java.io.File(dir, "sessions.json").readText())
            assertTrue(json.length() <= 128)
            assertEquals("id-139", diary.session("scope-139"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun echoedJsonSessionSurvivesBrainRecreationAndDoesNotReplayHistory() = sessionScenario(false)
    @Test fun echoedSseSessionSurvivesBrainRecreationAndDoesNotReplayHistory() = sessionScenario(true)
    private fun sessionScenario(sse: Boolean) = runBlocking {
        val requests = mutableListOf<Pair<String?, JSONObject>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { e ->
            requests += e.requestHeaders.getFirst("X-Hermes-Session-Id") to JSONObject(e.requestBody.reader().readText())
            e.responseHeaders.set("X-Hermes-Session-Id", "server-session")
            val text = if (sse) "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                else """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""
            e.sendResponseHeaders(200, 0)
            e.responseBody.use { it.write(text.toByteArray()) }
            e.close()
        }
        server.createContext("/api/sessions/server-session") { e ->
            e.sendResponseHeaders(200, 0)
            e.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.createContext("/capabilities") { e ->
            e.sendResponseHeaders(200, 0)
            e.responseBody.use { it.write("""{"features":{"session_continuity_header":"X-Hermes-Session-Id"}}""".toByteArray()) }
        }
        server.start()
        val dir = Files.createTempDirectory("brain-session").toFile()
        try {
            val config = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, apiKey = "test-key", baseUrl = "http://127.0.0.1:${server.address.port}")
            val old = (1..15).map { Exchange("q$it", "a$it") }
            withContext(ConversationContext(Diary(dir), "conversation")) { Brain({ config }).answer("first", Locale.ENGLISH, old) }
            withContext(ConversationContext(Diary(dir), "conversation")) { Brain({ config }).answer("second", Locale.ENGLISH, old + Exchange("first", "ok")) }
            server.removeContext("/api/sessions/server-session")
            try {
                withContext(ConversationContext(Diary(dir), "conversation")) { Brain({ config }).answer("expired", Locale.ENGLISH, old) }
                fail("expired session must not execute")
            } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("Request not replayed")) }
            for (lostResponse in listOf(false, true)) {
                // Retry/Again must refuse even when caller lost the answer but server performed tools.
                val retry = runCatching {
                    withContext(ConversationContext(Diary(dir), "conversation", replay = true)) {
                        Brain({ config }).answer(if (lostResponse) "lost tool response" else "successful tool question", Locale.ENGLISH, old)
                    }
                }
                assertTrue("server-backed replay must refuse", retry.isFailure)
                assertTrue(retry.exceptionOrNull()!!.message!!.contains("Retry/Again"))
            }
            assertEquals(2, requests.size)
            assertEquals(32, requests[0].second.getJSONArray("messages").length())
            assertNull(requests[0].first)
            assertEquals("server-session", requests[1].first)
            assertEquals(2, requests[1].second.getJSONArray("messages").length())
            assertNotEquals(requests[0].second.getJSONArray("messages").getJSONObject(0).getString("content"),
                requests[1].second.getJSONArray("messages").getJSONObject(0).getString("content"))
            assertEquals("second", requests[1].second.getJSONArray("messages").getJSONObject(1).getString("content"))
            withContext(ConversationContext(Diary(dir), "conversation")) { Brain({ config.copy(apiKey = "changed") }).answer("changed key", Locale.ENGLISH, old) }
            assertNull(requests.last().first)
            withContext(ConversationContext(Diary(dir), "new-conversation")) { Brain({ config }).answer("fresh", Locale.ENGLISH) }
            assertNull(requests.last().first)
            val photo = Exchange("reference", "photo answer", image = byteArrayOf(1, 2))
            withContext(ConversationContext(Diary(dir), "photo")) { Brain({ config }).answer("look back", Locale.ENGLISH, listOf(photo)) }
            assertEquals("data:image/jpeg;base64,AQI=", requests.last().second.getJSONArray("messages").getJSONObject(1)
                .getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url"))
            val diary = Diary(dir)
            diary.record(TurnRecord("delete", 1, "delete", conversation = "photo"))
            diary.delete(setOf("delete"))
            withContext(ConversationContext(diary, "photo")) { Brain({ config }).answer("after delete", Locale.ENGLISH) }
            assertNull(requests.last().first)
            server.removeContext("/capabilities")
            withContext(ConversationContext(Diary(dir), "generic")) { Brain({ config }).answer("generic", Locale.ENGLISH, old) }
            assertEquals(26, requests.last().second.getJSONArray("messages").length())
            assertNull(requests.last().first)
            withContext(ConversationContext(Diary(dir), "generic", replay = true)) { Brain({ config }).answer("generic retry", Locale.ENGLISH, old) }
            assertEquals("generic retry", requests.last().second.getJSONArray("messages").getJSONObject(25).getString("content"))
            assertNull(requests.last().first)
            Brain({ config }).phrase("auxiliary", Locale.ENGLISH)
            assertNull(requests.last().first)
        } finally { server.stop(0); dir.deleteRecursively() }
    }
}

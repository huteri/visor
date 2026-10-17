package com.macedonga.visor.core.assistant

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrainProgressStatusIntegrationTest {
    @Test fun auxiliaryUsesOnlySanitizedMetadataWhileOriginalRequestKeepsRunning() = runBlocking {
        val requests = CopyOnWriteArrayList<JSONObject>()
        val progress = CompletableDeferred<ActivitySnapshot>()
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/chat/completions") { exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            requests += body
            val isMain = body.getJSONArray("messages").getJSONObject(1).getString("content") == "synthetic-private-question"
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { writer ->
                if (isMain) {
                    writer.write("event: hermes.tool.progress\ndata: {\"tool\":\"web_search\",\"toolCallId\":\"synthetic-private-id\",\"label\":\"synthetic-private-arguments\",\"status\":\"running\"}\n\n")
                    writer.flush()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                val text = if (isMain) "original final" else "A search is still running."
                writer.write("data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
            }
            exchange.close()
        }
        server.start()
        val config = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, model = "synthetic-model",
            baseUrl = "http://127.0.0.1:${server.address.port}", instructions = "synthetic-private-instructions")
        val brain = Brain({ config })
        try {
            val original = async { brain.answer("synthetic-private-question", Locale.ENGLISH, configuration = config,
                onProgress = { progress.complete(it) }) }
            val snapshot = withTimeout(3_000) { progress.await() }
            val text = brain.statusPhrase(snapshot, 15_000, Locale.ENGLISH, config)
            assertEquals("A search is still running.", text)
            assertFalse("auxiliary request must not replace the original", original.isCompleted)
            assertEquals(2, requests.size)
            val auxiliary = requests.last()
            assertEquals("none", auxiliary.getString("tool_choice"))
            assertEquals(0, auxiliary.getJSONArray("tools").length())
            assertEquals(2, auxiliary.getJSONArray("messages").length())
            assertFalse(auxiliary.toString().contains("synthetic-private"))
            assertEquals("elapsed_seconds=15; request=running; search:running=1,completed=0",
                auxiliary.getJSONArray("messages").getJSONObject(1).getString("content"))
            release.countDown()
            assertEquals("original final", original.await().text)
        } finally { release.countDown(); server.stop(0); executor.shutdownNow() }
    }

    @Test fun auxiliaryAllowsMeasuredBackendLatencyWithoutBlockingOriginalTurn() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.close()
            Thread.sleep(4_500)
            val bytes = "{\"choices\":[{\"message\":{\"content\":\"A task is still running.\"}}]}".toByteArray()
            runCatching {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        val config = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE,
            baseUrl = "http://127.0.0.1:${server.address.port}")
        val brain = Brain({ config })
        val status = ProgressStatus(DiagnosticLease({ 0L }, { true }, 120_000), { 0L },
            { snapshot, elapsed -> brain.statusPhrase(snapshot, elapsed, Locale.ENGLISH, config) })
        try {
            assertEquals("A task is still running.", status.next())
        } finally { status.stop(); server.stop(0) }
    }

    @Test fun auxiliaryDoesNotRetryCompatibilityErrorsOrTouchMemory() = runBlocking {
        var requests = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.close()
            requests++
            val bytes = "{\"error\":{\"message\":\"reasoning tools max_completion_tokens refused\"}}".toByteArray()
            exchange.sendResponseHeaders(400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        val config = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE,
            baseUrl = "http://127.0.0.1:${server.address.port}")
        val book = object : MemoryBook {
            override val memories: kotlinx.coroutines.flow.StateFlow<List<Memory>> get() = error("must not read memories")
            override fun remember(text: String): Memory = error("must not remember")
            override fun forget(id: String): Memory? = error("must not forget")
        }
        try {
            try { Brain({ error("use captured config") }, book).statusPhrase(ActivitySnapshot(), 15_000, Locale.ENGLISH, config); fail("expected refusal") }
            catch (_: IllegalStateException) { }
            assertEquals(1, requests)
        } finally { server.stop(0) }
    }
}

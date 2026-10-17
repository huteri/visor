package com.macedonga.visor.core.assistant

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real HTTP + Brain parser + production turn/controller/wire; only audio and time are faked. */
class ProgressBrainIntegrationTest {
    private suspend fun exercise(ending: String, success: Boolean) {
        val arrived = CompletableDeferred<Unit>()
        val modelDone = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.bufferedReader().use { it.readText() }
            requests++
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { writer ->
                writer.write("event: hermes.tool.progress\ndata: {\"tool\":\"terminal\",\"toolCallId\":\"synthetic-id\",\"status\":\"running\",\"emoji\":\"x\",\"label\":\"synthetic-preview-never-speak\"}\n\n")
                writer.write("event: hermes.status\ndata: {\"status\":\"running\"}\n\n")
                writer.write("event: hermes.commentary\ndata: {\"text\":\"synthetic-interim-never-speak\"}\n\n")
                writer.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"synthetic-reasoning-never-speak\"},\"finish_reason\":null}]}\n\n")
                writer.flush()
                arrived.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                writer.write(ending)
            }
            exchange.close()
        }
        server.start()
        var ms = 0L
        val spoken = mutableListOf<String>()
        val types = mutableListOf<Int?>()
        val audioAt = mutableListOf<Long>()
        var commits = 0
        var failures = 0
        var released = 0
        val lifetime = DiagnosticLifetime()
        val brain = Brain({ error("must use the immutable request configuration") })
        val config = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, spokenProgress = true,
            model = "synthetic-model", baseUrl = "http://127.0.0.1:${server.address.port}")
        val pause: suspend (Long) -> Unit = { duration ->
            arrived.await()
            if (ms >= 20_000) { release.countDown(); modelDone.await() }
            ms += duration
            yield()
        }
        try {
            try {
                ProgressTurn({ ms }).run(lifetime, lifetime.ticket(), setOf("request", "mic"), "owned-stream",
                    controller = { lease, stream ->
                        val wire = ProgressWire(lease, "request", "question", "ack", stream, 1, 2,
                            { ms }, { 1000L }, pause) { _, _, type ->
                            types += type
                            if (type == MT_STREAM_AUDIO || type == null) audioAt += ms
                        }
                        ProgressSpeech({ ms }, pause, { text -> spoken += text; ShortArray(960) { 1 } },
                            { object : ProgressEncoder {
                                override suspend fun encode(pcm: ShortArray, end: Boolean) =
                                    if (end) emptyList() else listOf(OpusPacket(byteArrayOf(0), 20_000))
                                override fun close() { released++ }
                            } }, wire)
                    }, acknowledgement = "ack", progress = "working",
                    model = { try { brain.answer("question", Locale.ENGLISH, configuration = config) } finally { modelDone.complete(Unit) } },
                    completed = { assertEquals("actual final", it.reply.text); commits++ }, failed = { failures++ })
                assertTrue("failed parser must not become a successful answer", success)
            } catch (error: IllegalStateException) {
                if (success) throw error
            }
            assertEquals(1, requests)
            assertEquals(if (success) 1 else 0, commits)
            assertEquals(if (success) 0 else 1, failures)
            assertEquals(if (success) 1 else 0, types.count { it == MT_STREAM_END })
            assertEquals(1, types.count { it == MT_STREAM_HEADER })
            assertEquals(1, released)
            assertTrue(audioAt.first() < 20_000)
            assertTrue(spoken.contains("working"))
            assertTrue(spoken.all { it in setOf("ack", "working", "actual final") })
            assertEquals(success, spoken.contains("actual final"))
        } finally { release.countDown(); server.stop(0) }
    }

    @Test fun `live backend completion after sixteen seconds speaks only actual final not lifecycle labels or reasoning`() = runBlocking {
        exercise("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"actual final\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", true)
    }

    @Test fun `real approval parser failure cannot become progressive final speech`() = runBlocking {
        exercise("event: approval.request\ndata: {\"id\":\"synthetic-approval\"}\n\n", false)
    }

    @Test fun `real stream error cannot become progressive final speech`() = runBlocking {
        exercise("event: error\ndata: {\"error\":{\"message\":\"synthetic-failure\"}}\n\n", false)
    }

    @Test fun `real premature EOF cannot speak partial content as final`() = runBlocking {
        exercise("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"synthetic-partial-never-speak\"},\"finish_reason\":null}]}\n\n", false)
    }
}

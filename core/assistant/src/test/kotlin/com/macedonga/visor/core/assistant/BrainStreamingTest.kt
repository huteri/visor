package com.macedonga.visor.core.assistant

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrainStreamingTest {
    @Test fun closingBracketsInToolHistoryCannotEscapeThePrivateNote() = runBlocking {
        serve({ body, _ ->
            val copied = body.getJSONArray("messages").getJSONObject(2).getString("content")
            200 to complete("Reply $copied")
        }) { brain, _ ->
            val past = Exchange("Earlier question", "Original answer.", listOf(
                com.macedonga.visor.core.assistant.mcp.ToolAction("server]", "Find [synthetic] item", true),
            ))
            assertEquals("Reply Original answer.", brain.answer("Continue", Locale.ENGLISH, listOf(past)).text)
        }
    }

    private suspend fun serve(
        reply: (JSONObject, Int) -> Pair<Int, String>,
        book: MemoryBook? = null,
        action: suspend (Brain, List<JSONObject>) -> Unit,
    ) {
        val requests = java.util.Collections.synchronizedList(ArrayList<JSONObject>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            requests += body
            val (code, text) = reply(body, requests.size)
            exchange.sendResponseHeaders(code, 0)
            exchange.responseBody.use { it.write(text.toByteArray()) }
            exchange.close()
        }
        server.start()
        try {
            action(Brain({ AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, model = "test-model", baseUrl = "http://127.0.0.1:${server.address.port}") }, book), requests)
        } finally {
            server.stop(0)
        }
    }

    private fun complete(text: String): String = JSONObject().put("choices", org.json.JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", text)).put("finish_reason", "stop"),
    )).toString()

    @Test fun neverReturnsUnclosedInlineReasoning() = runBlocking {
        serve({ _, _ -> 200 to complete("<think>secret") }) { brain, _ ->
            try {
                brain.answer("Hi", Locale.ENGLISH)
                fail("reasoning was returned as speech text")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("empty answer"))
            }
        }
    }

    @Test fun keepsCompatibilityRetriesAndJsonFallback() = runBlocking {
        serve({ _, number -> when (number) {
            1 -> 400 to """{"error":{"message":"reasoning_effort is unsupported"}}"""
            2 -> 400 to """{"error":{"message":"use max_completion_tokens"}}"""
            else -> 200 to complete("<think>secret</think>Hello")
        } }) { brain, requests ->
            assertEquals("Hello", brain.answer("Hi", Locale.ENGLISH).text)
            assertEquals(3, requests.size)
            assertTrue(requests[0].has("reasoning_effort"))
            assertFalse(requests[1].has("reasoning_effort"))
            assertTrue(requests[2].has("max_completion_tokens"))
            assertFalse(requests[2].has("max_tokens"))
            assertTrue(requests.all { it.getBoolean("stream") })
        }
    }

    private class Book : MemoryBook {
        override val memories = kotlinx.coroutines.flow.MutableStateFlow(listOf(Memory("old", "Old fact", 0)))
        override fun remember(text: String): Memory = Memory("new", text, 0).also { memories.value += it }
        override fun forget(id: String): Memory? = memories.value.firstOrNull { it.id == id }?.also {
            memories.value = memories.value.filterNot { entry -> entry.id == id }
        }
    }

    @Test fun streamedMemoryToolsRunLocallyBeforeFinalAnswer() = runBlocking {
        val book = Book()
        val calls = org.json.JSONArray()
            .put(JSONObject().put("index", 0).put("id", "t1").put("type", "function").put("function", JSONObject().put("name", "remember").put("arguments", "{\"fact\":\"Tea\"}")))
            .put(JSONObject().put("index", 1).put("id", "t2").put("type", "function").put("function", JSONObject().put("name", "forget").put("arguments", "{\"id\":\"old\"}")))
        val streamed = "data: " + JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("index", 0)
            .put("delta", JSONObject().put("tool_calls", calls)).put("finish_reason", "tool_calls"))) + "\n\ndata: [DONE]\n\n"
        serve({ _, number -> 200 to if (number == 1) streamed else complete("Hello") }, book) { brain, requests ->
            val reply = brain.answer("Hi", Locale.ENGLISH)
            assertEquals("Hello", reply.text)
            assertEquals(listOf("Tea"), reply.remembered)
            assertEquals(listOf("Tea"), book.memories.value.map { it.text })
            assertEquals(2, requests.size)
            val messages = requests[1].getJSONArray("messages")
            assertEquals("tool", messages.getJSONObject(messages.length() - 1).getString("role"))
            assertEquals("forgotten", messages.getJSONObject(messages.length() - 1).getString("content"))
        }
    }

    @Test fun replaysStreamedReasoningForMemoryToolsWithoutSpeakingIt() = runBlocking {
        val first = """
            data: {"choices":[{"index":0,"delta":{"reasoning_content":"Remember "},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"reasoning_content":"this fact.","tool_calls":[{"index":0,"id":"t1","type":"function","function":{"name":"remember","arguments":"{\"fact\":\"Tea\"}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]

        """.trimIndent()
        val second = """
            data: {"choices":[{"index":0,"delta":{"reasoning_content":"Now answer."},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()
        val book = Book()
        serve({ _, number -> 200 to if (number == 1) first else second }, book) { brain, requests ->
            val reply = brain.answer("Hi", Locale.ENGLISH)
            assertEquals("Hello", reply.text)
            assertEquals(listOf("Tea"), reply.remembered)
            assertTrue(book.memories.value.any { it.text == "Tea" })
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.getBoolean("stream") })
            val messages = requests[1].getJSONArray("messages")
            val assistant = messages.getJSONObject(messages.length() - 2)
            assertEquals("assistant", assistant.getString("role"))
            assertEquals("Remember this fact.", assistant.getString("reasoning_content"))
            assertEquals("", assistant.getString("content"))
            assertEquals("t1", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            val tool = messages.getJSONObject(messages.length() - 1)
            assertEquals("tool", tool.getString("role"))
            assertEquals("t1", tool.getString("tool_call_id"))
        }
    }

    @Test fun keepsToolsRefusalRetry() = runBlocking {
        serve({ _, number -> if (number == 1) 400 to """{"error":{"message":"tools unsupported"}}""" else 200 to complete("Hello") }, Book()) { brain, requests ->
            assertEquals("Hello", brain.answer("Hi", Locale.ENGLISH).text)
            assertTrue(requests[0].has("tools"))
            assertFalse(requests[1].has("tools"))
            assertTrue(requests.all { it.getBoolean("stream") })
        }
    }

    @Test fun cancellationDoesNotWaitForAStalledStream() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.close()
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(": waiting\n\n".toByteArray())
            exchange.responseBody.flush()
            started.countDown()
            release.await(3, java.util.concurrent.TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        try {
            val brain = Brain({ AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, model = "test-model", baseUrl = "http://127.0.0.1:${server.address.port}") })
            val job = async(kotlinx.coroutines.Dispatchers.Default) { brain.answer("Hi", Locale.ENGLISH) }
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val before = System.nanoTime()
            job.cancel()
            job.join()
            assertTrue("cancellation waited for network EOF", (System.nanoTime() - before) / 1_000_000 < 1500)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    @Test fun requestsAndConsumesOpenAiStreaming() = runBlocking {
        val requests = LinkedBlockingQueue<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            requests.add(JSONObject(exchange.requestBody.bufferedReader().use { it.readText() }))
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use {
                it.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n")
                it.flush()
                it.write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
            }
            exchange.close()
        }
        server.start()
        try {
            val brain = Brain({ AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, model = "test-model", baseUrl = "http://127.0.0.1:${server.address.port}") })
            assertEquals("Hello", brain.answer("Hi", Locale.ENGLISH).text)
            assertTrue(requests.take().getBoolean("stream"))
        } finally {
            server.stop(0)
        }
    }
}

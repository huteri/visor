package com.macedonga.visor.core.assistant

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrainEndpointProtocolTest {
    @Test fun responsesRequestsJsonAndParsesOutput() = runBlocking {
        serve("/responses", """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Search answer."}]}]}""") { brain, base ->
            val config = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, baseUrl = base, model = "test-model")
            val answer = suspendCoroutineUninterceptedOrReturn<String> { continuation ->
                Brain::class.java.declaredMethods.single { it.name == "responses" }
                    .apply { isAccessible = true }
                    .invoke(brain, config, "test-model", "System", emptyList<Exchange>(), "Search", null, 100, true, null, continuation)
            }
            assertEquals("Search answer.", answer)
        }
    }

    @Test fun generateContentRequestsJsonAndParsesCandidates() = runBlocking {
        serve("/models/test:generateContent", """{"candidates":[{"content":{"parts":[{"text":"Gemini answer."}]}}]}""") { brain, base ->
            val answer = suspendCoroutineUninterceptedOrReturn<JSONObject> { continuation ->
                Brain::class.java.getDeclaredMethod("post", String::class.java, Map::class.java, JSONObject::class.java, kotlin.coroutines.Continuation::class.java)
                    .apply { isAccessible = true }
                    .invoke(brain, "$base/models/test:generateContent", mapOf("x-goog-api-key" to "test-key"), JSONObject().put("contents", org.json.JSONArray()), continuation)
            }
            assertEquals("Gemini answer.", answer.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text"))
        }
    }

    private suspend fun serve(path: String, json: String, action: suspend (Brain, String) -> Unit) {
        var request: JSONObject? = null
        var accept: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            request = body
            accept = exchange.requestHeaders.getFirst("Accept")
            // Responses SSE is a different protocol from Chat Completions SSE.
            val response = if (body.optBoolean("stream")) "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":$json}\n\n" else json
            exchange.responseHeaders.set("Content-Type", if (body.optBoolean("stream")) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(response.toByteArray()) }
            exchange.close()
        }
        server.start()
        try {
            action(Brain({ AssistantConfig() }), "http://127.0.0.1:${server.address.port}")
            assertNotNull(request)
            assertFalse("JSON endpoints must not opt into streaming", request!!.optBoolean("stream"))
            assertFalse("JSON endpoints must not advertise SSE", accept.orEmpty().contains("text/event-stream"))
        } finally { server.stop(0) }
    }
}

package com.macedonga.visor.core.assistant

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Combined upstream model tools/probing and fork session continuity boundaries. */
class BrainMergeIntegrationTest {
    @Test fun endConversationSurvivesSerializedConversationAndToolRound() = runBlocking {
        val host = UUID.randomUUID().toString()
        val requests = mutableListOf<Pair<String?, JSONObject>>()
        val dir = Files.createTempDirectory("end-conversation-session").toFile()
        TestHttpConnections.handlers[host] = { url -> object : HttpURLConnection(url) {
            private val payload = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = payload
            override fun getResponseCode(): Int {
                if (url.path == "/chat/completions") requests += getRequestProperty("X-Hermes-Session-Id") to JSONObject(payload.toString("UTF-8"))
                return 200
            }
            override fun getHeaderField(name: String) = if (name == "X-Hermes-Session-Id") "ending-session" else null
            override fun getInputStream() = (when (url.path) {
                "/capabilities" -> """{"features":{"session_continuity_header":"X-Hermes-Session-Id"}}"""
                else -> if (requests.size == 1) """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"end","type":"function","function":{"name":"end_conversation","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
                    else """{"choices":[{"message":{"content":"Goodbye."},"finish_reason":"stop"}]}"""
            }).byteInputStream()
        } }
        try {
            val config = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, apiKey = "key", baseUrl = "visor-test://$host")
            val result = withContext(ConversationContext(Diary(dir), "c")) { Brain({ config }).answer("That's all", Locale.ENGLISH, canEnd = true) }
            assertTrue(result.ends)
            assertEquals("Goodbye.", result.text)
            assertTrue(requests.first().second.getJSONArray("tools").toString().contains("end_conversation"))
            assertEquals(2, requests.size)
        } finally { TestHttpConnections.handlers.remove(host); dir.deleteRecursively() }
    }

    @Test fun genericReasoning400StillAllowsThinkingBudgetProbe() = thinkingProbe(false)
    @Test fun acceptedHermesThinkingResponseCannotReplayPost() = thinkingProbe(true)

    private fun thinkingProbe(session: Boolean) = runBlocking {
        val host = UUID.randomUUID().toString()
        val requests = mutableListOf<JSONObject>()
        val dir = Files.createTempDirectory("thinking-session").toFile()
        TestHttpConnections.handlers[host] = { url -> object : HttpURLConnection(url) {
            private val payload = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = payload
            override fun getResponseCode(): Int {
                if (url.path == "/chat/completions") {
                    requests += JSONObject(payload.toString("UTF-8"))
                    if (requests.size == 1) return 400
                }
                return 200
            }
            override fun getHeaderField(name: String) = if (session && requests.size >= 2 && name == "X-Hermes-Session-Id") "thinking-session" else null
            override fun getErrorStream() = """{"error":{"message":"Request contains an invalid argument"}}""".byteInputStream()
            override fun getInputStream() = (when (url.path) {
                "/capabilities" -> if (session) """{"features":{"session_continuity_header":"X-Hermes-Session-Id"}}""" else "{}"
                else -> if (requests.size == 2) """{"choices":[{"message":{"content":""},"finish_reason":"length"}]}"""
                    else """{"choices":[{"message":{"content":"Answer."},"finish_reason":"stop"}]}"""
            }).byteInputStream()
        } }
        try {
            val config = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, apiKey = "key", baseUrl = "visor-test://$host")
            val result = runCatching { withContext(ConversationContext(Diary(dir), "c")) { Brain({ config }).answer("Question", Locale.ENGLISH) } }
            assertTrue(requests.first().has("reasoning_effort"))
            assertFalse(requests[1].has("reasoning_effort"))
            if (session) {
                assertEquals("Accepted session requests must never be silently replayed", 2, requests.size)
                assertTrue(result.isFailure)
            } else {
                assertEquals("Answer.", result.getOrThrow().text)
                assertEquals(3, requests.size)
                assertTrue(requests[2].getInt("max_tokens") > requests[1].getInt("max_tokens"))
            }
        } finally { TestHttpConnections.handlers.remove(host); dir.deleteRecursively() }
    }
}

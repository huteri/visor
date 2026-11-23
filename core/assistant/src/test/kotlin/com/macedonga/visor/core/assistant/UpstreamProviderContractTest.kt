package com.macedonga.visor.core.assistant

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpstreamProviderContractTest {
    @Test fun apiKeyTransportsDoNotOptIntoSseButHermesDoes() {
        val brain = Brain({ AssistantConfig() })
        val protocol = Brain::class.java.declaredClasses.single { it.simpleName == "ModelProtocol" }
        val request = Brain::class.java.declaredMethods.single { it.name == "request" }.apply { isAccessible = true }
        for (mode in protocol.enumConstants) {
            val payload = ByteArrayOutputStream()
            val http = object : HttpURLConnection(URL("http://localhost")) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getOutputStream() = payload
                override fun getResponseCode() = 200
                override fun getInputStream() = "{}".byteInputStream()
            }
            request.invoke(brain, http, emptyMap<String, String>(), JSONObject(), mode, 45000, { _: String -> }, {})
            val hermes = mode.toString() == "HERMES"
            assertEquals(mode.toString(), hermes, JSONObject(payload.toString("UTF-8")).optBoolean("stream"))
            assertEquals(mode.toString(), hermes, http.getRequestProperty("accept").contains("text/event-stream"))
        }
    }

    @Test fun responsesKeepsUpstreamHistoryWindow() = runBlocking {
        val chat = org.mockito.kotlin.mock<ChatGptPlan>()
        org.mockito.kotlin.whenever(chat.respond(org.mockito.kotlin.any())).thenReturn(JSONObject("""{"output":[{"type":"message","content":[{"type":"output_text","text":"Answer"}]}]}"""))
        val c = AssistantConfig(provider = Provider.CHATGPT, chatGpt = "signed-in")
        Brain({ c }, chatGpt = chat).answer("Current", Locale.ENGLISH, (0..99).map { Exchange("Question $it", "Answer $it") })
        val body = org.mockito.kotlin.argumentCaptor<JSONObject>()
        org.mockito.kotlin.verify(chat).respond(body.capture())
        val input = body.firstValue.getJSONArray("input")
        assertEquals(12 * 2 + 1, input.length())
        assertEquals("Question ${100 - 12}", input.getJSONObject(0).getString("content"))
    }

    @Test fun genericJsonKeepsLocalToolsPromptAndThinkingProbe() = runBlocking {
        val host = UUID.randomUUID().toString()
        val requests = mutableListOf<JSONObject>()
        TestHttpConnections.handlers[host] = { url -> object : HttpURLConnection(url) {
            private val payload = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = payload
            override fun getResponseCode(): Int {
                assertEquals("/chat/completions", url.path)
                requests += JSONObject(payload.toString("UTF-8"))
                return if (requests.size == 1) 400 else 200
            }
            override fun getErrorStream() = """{"error":{"message":"reasoning unsupported"}}""".byteInputStream()
            override fun getInputStream() = (if (requests.size == 2)
                """{"choices":[{"message":{"content":"","reasoning_content":"thinking"},"finish_reason":"length"}]}"""
                else """{"choices":[{"message":{"content":"Answer"},"finish_reason":"stop"}]}""").byteInputStream()
        } }
        try {
            val c = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, baseUrl = "visor-test://$host", model = "test")
            assertEquals("Answer", Brain({ c }).answer("Question", Locale.ENGLISH, canEnd = true).text)
            assertEquals(3, requests.size)
            assertTrue(requests.first().getJSONArray("tools").toString().contains("end_conversation"))
            assertTrue(requests.first().getJSONArray("messages").getJSONObject(0).getString("content").contains("remember"))
            assertFalse(requests.any { it.optBoolean("stream") })
            assertTrue(requests.first().has("reasoning_effort"))
            assertFalse(requests[1].has("reasoning_effort"))
            assertTrue(requests[2].getInt("max_tokens") > requests[1].getInt("max_tokens"))
        } finally { TestHttpConnections.handlers.remove(host) }
    }
}

package com.macedonga.visor.core.assistant

import java.io.Reader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelResponseTest {
    private fun parse(text: String, claude: Boolean = false): JSONObject =
        ModelResponse.read(FragmentedReader(text), claude)

    private class FragmentedReader(private val text: String) : Reader() {
        private var offset = 0
        override fun read(buffer: CharArray, start: Int, length: Int): Int {
            if (offset == text.length) return -1
            buffer[start] = text[offset++]
            return 1
        }
        override fun close() = Unit
    }

    private fun sse(vararg events: JSONObject): String =
        events.joinToString("") { "data: $it\n\n" }

    private fun chunk(delta: JSONObject, finish: Any = JSONObject.NULL, index: Int = 0) =
        JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("index", index).put("delta", delta).put("finish_reason", finish)))

    // Sanitized shapes from api_server_openai_routes.py's Chat Completions writer.
    @Test fun hermesNotificationsNeverBecomeAnswerOrLocalToolCalls() {
        val input = sse(chunk(JSONObject().put("role", "assistant"))) +
            "event: hermes.tool.progress\ndata: {\"tool\":\"web_search\",\"emoji\":\"search\",\"label\":\"Search example\",\"toolCallId\":\"call_safe\",\"status\":\"running\"}\n\n" +
            "event: hermes.status\ndata: {\"kind\":\"warning\",\"text\":\"Waiting for provider\"}\n\n" +
            ": keepalive\n\n" +
            sse(chunk(JSONObject().put("reasoning_content", "private thought")), chunk(JSONObject().put("content", "Answer"))) +
            "event: hermes.tool.progress\ndata: {\"tool\":\"web_search\",\"toolCallId\":\"call_safe\",\"status\":\"completed\"}\n\n" +
            "event: hermes.future.extension\ndata: not even JSON\n\n" +
            "event: hermes.future.extension\ndata: [DONE]\n\n" +
            sse(chunk(JSONObject().put("content", " only")), chunk(JSONObject(), "stop")) + "data: [DONE]\n\n"
        val message = parse(input).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        assertEquals("Answer only", message.getString("content"))
        assertEquals("private thought", message.getString("reasoning_content"))
        assertFalse(message.has("tool_calls"))
    }

    @Test fun approvalChoicesFailClosedBeforeAnswerSchemaOrDone() {
        // api_server.py _approval_request_event exposes string decisions, not answer choices.
        for (payload in listOf(
            """{"run_id":"chatcmpl-safe","event":"approval.request","timestamp":1.0,"session_id":"safe","command":"example","choices":["once","session","always","deny"]}""",
            "[DONE]", "not JSON",
        )) {
            val input = sse(chunk(JSONObject().put("content", "partial"))) +
                "event: approval.request\ndata: $payload\n\n" +
                sse(chunk(JSONObject(), "stop")) + "data: [DONE]\n\n"
            val failure = assertThrows(IllegalStateException::class.java) { parse(input) }
            assertTrue(failure.message.orEmpty().contains("approval", ignoreCase = true))
            assertTrue(failure.message.orEmpty().contains("phone", ignoreCase = true))
            assertTrue(failure.message.orEmpty().contains("unsupported", ignoreCase = true))
        }
    }

    @Test fun rejectsUnsuccessfulTerminalReasonsEvenWithDone() {
        for (reason in listOf("error", "incomplete", "length", "content_filter", "unexpected")) {
            val failure = assertThrows(IllegalStateException::class.java) {
                parse(sse(chunk(JSONObject().put("content", "partial")), chunk(JSONObject(), reason)) + "data: [DONE]\n\n")
            }
            assertTrue(failure.message.orEmpty().contains(reason))
        }
    }

    @Test fun rejectsHermesFailureMetadataRegardlessOfStopReason() {
        for (metadata in listOf(
            """{"completed":false,"partial":false,"failed":true,"error":"agent unavailable","error_code":"agent_error"}""",
            """{"completed":false,"partial":true,"failed":false,"error":"output truncated","error_code":"output_truncated"}""",
            """{"completed":false,"partial":false,"failed":false,"error":null}""",
            """{"completed":true,"partial":true,"failed":false,"error":null}""",
        )) {
            val terminal = chunk(JSONObject(), "stop").put("hermes", JSONObject(metadata))
            val failure = assertThrows(IllegalStateException::class.java) {
                parse(sse(chunk(JSONObject().put("content", "partial")), terminal) + "data: [DONE]\n\n")
            }
            assertTrue(failure.message.orEmpty().contains("model stream"))
        }
    }

    @Test fun malformedDefaultAnswerFramesAreNotOptionalExtensions() {
        for (bad in listOf(
            "not JSON", "{}", "{\"choices\":[]}",
            "{\"choices\":[{\"index\":0,\"delta\":\"wrong schema\"}]}",
        )) {
            assertThrows(Exception::class.java) {
                parse("data: $bad\n\n" + sse(chunk(JSONObject().put("content", "answer")), chunk(JSONObject(), "stop")) + "data: [DONE]\n\n")
            }
        }
    }

    @Test fun openAiAccumulatesInterleavedToolArguments() {
        fun call(index: Int, arguments: String, id: String? = null, name: String? = null) =
            JSONObject().put("index", index).put("id", id).put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", arguments))
        val first = org.json.JSONArray().put(call(0, "{\"fact\":", "call_1", "remember"))
            .put(call(1, "{\"id\":", "call_2", "forget"))
        val second = org.json.JSONArray().put(call(1, "\"old\"}" )).put(call(0, "\"Tea\"}"))
        val result = parse(sse(
            chunk(JSONObject().put("tool_calls", first).put("reasoning_content", "secret")),
            chunk(JSONObject().put("tool_calls", second)),
            chunk(JSONObject(), "tool_calls"),
        ) + "data: [DONE]\n\n")
        val message = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        assertEquals("", message.getString("content"))
        val calls = message.getJSONArray("tool_calls")
        assertEquals(2, calls.length())
        assertEquals("call_1", calls.getJSONObject(0).getString("id"))
        assertEquals("remember", calls.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("Tea", JSONObject(calls.getJSONObject(0).getJSONObject("function").getString("arguments")).getString("fact"))
        assertEquals("old", JSONObject(calls.getJSONObject(1).getJSONObject("function").getString("arguments")).getString("id"))
    }

    private fun event(json: String) = JSONObject(json)

    @Test fun claudeAccumulatesTextAndToolInput() {
        val result = parse(sse(
            event("""{"type":"message_start","message":{"id":"m1","role":"assistant","content":[],"stop_reason":null}}"""),
            event("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""),
            event("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}"""),
            event("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}"""),
            event("""{"type":"content_block_stop","index":0}"""),
            event("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1","name":"remember","input":{}}}"""),
            JSONObject().put("type", "content_block_delta").put("index", 1).put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", "{\"fact\":")),
            JSONObject().put("type", "content_block_delta").put("index", 1).put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", "\"Tea\"}")),
            event("""{"type":"content_block_stop","index":1}"""),
            event("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""),
            event("""{"type":"message_stop"}"""),
        ), true)
        assertEquals("m1", result.getString("id"))
        assertEquals("tool_use", result.getString("stop_reason"))
        val blocks = result.getJSONArray("content")
        assertEquals("Hello", blocks.getJSONObject(0).getString("text"))
        assertEquals("Tea", blocks.getJSONObject(1).getJSONObject("input").getString("fact"))
        assertEquals("t1", blocks.getJSONObject(1).getString("id"))
    }

    @Test fun rejectsIncompleteClaudeStreams() {
        val start = sse(event("""{"type":"message_start","message":{"role":"assistant","content":[],"stop_reason":null}}"""))
        val block = sse(event("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"partial"}}"""))
        val finish = sse(event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""))
        val stop = sse(event("""{"type":"message_stop"}"""))
        for (input in listOf(start + block, start + finish, start + stop, start + block + finish + stop, stop)) {
            assertThrows(IllegalStateException::class.java) { parse(input, true) }
        }
    }

    @Test fun handlesMultilineCrLfCommentsAndUsageChunks() {
        val input = "\ufeff: comment\r\nid: 1\r\nretry: 10\r\nevent: message\r\ndata: {\r\ndata: \"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\r\n\r\n" +
            sse(chunk(JSONObject().put("content", "ignored"), "stop", 1), chunk(JSONObject(), "stop"), event("""{"choices":[],"usage":{"total_tokens":10}}""")) + "data: [DONE]\n\n"
        assertEquals("Hi", parse(input).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
    }

    @Test fun rejectsIncompleteToolArgumentsEvenWithDone() {
        val delta = event("""{"tool_calls":[{"index":0,"id":"t1","function":{"name":"remember","arguments":"{"}}]}""")
        assertThrows(Exception::class.java) {
            parse(sse(chunk(delta), chunk(JSONObject(), "tool_calls")) + "data: [DONE]\n\n")
        }
    }

    @Test fun checksCancellationWhileReading() {
        var checks = 0
        val cancellation = java.util.concurrent.CancellationException("cancelled")
        val thrown = assertThrows(java.util.concurrent.CancellationException::class.java) {
            ModelResponse.read(FragmentedReader(": ping\n\n".repeat(100)), false) {
                if (++checks == 5) throw cancellation
            }
        }
        assertSame(cancellation, thrown)
    }

    @Test fun preservesClaudeThinkingSignaturesAndWebSearchBlocks() {
        val citation = event("""{"type":"web_search_result_location","url":"https://example.com","title":"Example","encrypted_index":"x","cited_text":"result"}""")
        val result = parse(sse(
            event("""{"type":"message_start","message":{"role":"assistant","content":[]}}"""),
            event("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}"""),
            event("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"secret"}}"""),
            event("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed"}}"""),
            event("""{"type":"content_block_stop","index":0}"""),
            event("""{"type":"content_block_start","index":1,"content_block":{"type":"server_tool_use","id":"s1","name":"web_search","input":{}}}"""),
            event("""{"type":"content_block_stop","index":1}"""),
            event("""{"type":"content_block_start","index":2,"content_block":{"type":"web_search_tool_result","tool_use_id":"s1","content":[]}}"""),
            event("""{"type":"content_block_stop","index":2}"""),
            event("""{"type":"content_block_start","index":3,"content_block":{"type":"text","text":"Answer"}}"""),
            JSONObject().put("type", "content_block_delta").put("index", 3).put("delta", JSONObject().put("type", "citations_delta").put("citation", citation)),
            event("""{"type":"content_block_stop","index":3}"""),
            event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""),
            event("""{"type":"message_stop"}"""),
        ), true)
        val blocks = result.getJSONArray("content")
        assertEquals("secret", blocks.getJSONObject(0).getString("thinking"))
        assertEquals("signed", blocks.getJSONObject(0).getString("signature"))
        assertEquals("web_search", blocks.getJSONObject(1).getString("name"))
        assertEquals("s1", blocks.getJSONObject(2).getString("tool_use_id"))
        assertEquals("Answer", blocks.getJSONObject(3).getString("text"))
        assertEquals(citation.toString(), blocks.getJSONObject(3).getJSONArray("citations").getJSONObject(0).toString())
    }

    @Test fun preservesNonStreamingJsonCompatibility() {
        val openAi = """{"choices":[{"message":{"role":"assistant","content":"Hello"},"finish_reason":"stop"}]}"""
        val claude = """{"content":[{"type":"text","text":"Hello"}],"stop_reason":"end_turn"}"""
        assertEquals(JSONObject(openAi).toString(), parse(" \n" + openAi).toString())
        assertEquals(JSONObject(claude).toString(), parse(claude, true).toString())
    }

    @Test fun surfacesExplicitStreamErrors() {
        for (input in listOf(
            "event: error\ndata: {\"error\":{\"message\":\"overloaded\"}}\n\n",
            "data: {\"error\":{\"message\":\"overloaded\"}}\n\n",
            "event: error\ndata: overloaded\n\n",
            sse(chunk(JSONObject(), "error")
                .put("usage", event("""{"prompt_tokens":1,"completion_tokens":0,"total_tokens":1}"""))
                .put("error", event("""{"message":"overloaded","type":"agent_error"}"""))
                .put("hermes", event("""{"completed":false,"partial":false,"failed":true,"error":"overloaded","error_code":"agent_error"}"""))) + "data: [DONE]\n\n",
        )) {
            val error = assertThrows(IllegalStateException::class.java) { parse(input) }
            assertTrue(error.message.orEmpty().contains("overloaded"))
        }
    }

    @Test fun rejectsIncompleteOpenAiStreams() {
        val text = sse(chunk(JSONObject().put("content", "partial")))
        val finished = text + sse(chunk(JSONObject(), "stop"))
        for (input in listOf(text, finished, text + "data: [DONE]\n\n", ": ping\n\n", "")) {
            assertThrows(IllegalStateException::class.java) { parse(input) }
        }
        for (tail in listOf("data: [DON", "data: {\"choices\":")) {
            assertThrows(Exception::class.java) { parse(finished + tail) }
        }
    }

    @Test fun openAiAccumulatesFragmentedText() {
        val result = parse("""
            : heartbeat
            data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent())
        val choice = result.getJSONArray("choices").getJSONObject(0)
        assertEquals("Hello", choice.getJSONObject("message").getString("content"))
        assertEquals("stop", choice.getString("finish_reason"))
    }
}

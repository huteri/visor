package com.macedonga.visor.core.assistant

import java.io.Reader
import org.json.JSONArray
import org.json.JSONObject

/** Rebuild the non-streaming response consumed by Brain's tool loops. */
internal object ModelResponse {
    fun read(reader: Reader, claude: Boolean, checkCancelled: () -> Unit = {}): JSONObject {
        val input = java.io.PushbackReader(reader.buffered(), 1)
        var first: Int
        do {
            checkCancelled()
            first = input.read()
        } while (first != -1 && (first.toChar().isWhitespace() || first == 0xfeff))
        if (first != -1) input.unread(first)
        if (first == '{'.code) {
            val text = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count == -1) break
                text.append(buffer, 0, count)
            }
            val result = JSONObject(text.toString())
            if (result.has("error")) error(streamError(text.toString()))
            return result
        }
        return if (claude) readClaude(input, checkCancelled) else readOpenAi(input, checkCancelled)
    }

    private fun readOpenAi(reader: Reader, checkCancelled: () -> Unit): JSONObject {
        val message = JSONObject().put("role", "assistant").put("content", "")
        var finish = ""
        val calls = sortedMapOf<Int, JSONObject>()
        var done = false
        events(reader, checkCancelled) { type, data ->
            if (type == "error") error(streamError(data))
            if (type == "approval.request") {
                error("Hermes requires approval; approval is unsupported on glasses. Open an approval-capable Hermes client on your phone to review the request.")
            }
            // Named extensions are notifications, not Chat Completions chunks.
            if (type.isNotEmpty() && type != "message") return@events false
            if (data == "[DONE]") {
                done = true
                return@events true
            }
            val item = JSONObject(data)
            if (item.has("error")) error(streamError(data))
            item.optJSONObject("hermes")?.let { status ->
                check(status.optBoolean("completed", true) && !status.optBoolean("partial") && !status.optBoolean("failed")) {
                    "the model stream failed: " + status.optString("error", "incomplete Hermes response")
                }
            }
            val choices = item.getJSONArray("choices")
            if (choices.length() == 0) {
                check(item.optJSONObject("usage") != null) { "the model stream sent empty choices without usage" }
                return@events false
            }
            val choice = (0 until choices.length()).map { choices.getJSONObject(it) }
                .firstOrNull { it.optInt("index", 0) == 0 } ?: return@events false
            val delta = choice.getJSONObject("delta")
            if (!delta.isNull("content")) message.put("content", message.getString("content") + delta.getString("content"))
            if (!delta.isNull("reasoning_content")) {
                message.put("reasoning_content", message.optString("reasoning_content") + delta.getString("reasoning_content"))
            }
            val deltas = delta.optJSONArray("tool_calls")
            if (deltas != null) for (i in 0 until deltas.length()) {
                val part = deltas.getJSONObject(i)
                val call = calls.getOrPut(part.getInt("index")) {
                    JSONObject().put("type", "function").put("function", JSONObject().put("name", "").put("arguments", ""))
                }
                if (!part.isNull("id")) call.put("id", part.getString("id"))
                part.optJSONObject("function")?.let { fn ->
                    val target = call.getJSONObject("function")
                    for (field in listOf("name", "arguments")) {
                        if (!fn.isNull(field)) target.put(field, target.getString(field) + fn.getString(field))
                    }
                }
            }
            if (!choice.isNull("finish_reason")) {
                finish = choice.getString("finish_reason")
                check(finish == "stop" || finish == "tool_calls") {
                    "the model stream failed: finish_reason=$finish"
                }
            }
            false
        }
        check(done && finish.isNotEmpty()) { "the model stream ended before completion" }
        for (call in calls.values) {
            check(call.optString("id").isNotBlank() && call.getJSONObject("function").optString("name").isNotBlank()) {
                "the model stream ended with an incomplete tool call"
            }
            JSONObject(call.getJSONObject("function").getString("arguments"))
        }
        if (calls.isNotEmpty()) message.put("tool_calls", JSONArray(calls.values.toList()))
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", finish)))
    }

    private fun readClaude(reader: Reader, checkCancelled: () -> Unit): JSONObject {
        var message = JSONObject()
        val blocks = sortedMapOf<Int, JSONObject>()
        val inputs = HashMap<Int, StringBuilder>()
        val openBlocks = HashSet<Int>()
        var started = false
        var done = false
        events(reader, checkCancelled) { type, data ->
            if (type == "error") error(streamError(data))
            val item = JSONObject(data)
            if (item.has("error") || item.optString("type") == "error") error(streamError(data))
            when (item.optString("type", type)) {
                "message_start" -> {
                    message = item.getJSONObject("message")
                    started = true
                }
                "content_block_start" -> {
                    val index = item.getInt("index")
                    blocks[index] = item.getJSONObject("content_block")
                    openBlocks += index
                }
                "content_block_delta" -> {
                    val index = item.getInt("index")
                    val block = blocks.getValue(index)
                    val delta = item.getJSONObject("delta")
                    when (delta.getString("type")) {
                        "text_delta" -> block.put("text", block.optString("text") + delta.getString("text"))
                        "thinking_delta" -> block.put("thinking", block.optString("thinking") + delta.getString("thinking"))
                        "signature_delta" -> block.put("signature", block.optString("signature") + delta.getString("signature"))
                        "citations_delta" -> {
                            val citations = block.optJSONArray("citations") ?: JSONArray().also { block.put("citations", it) }
                            citations.put(delta.getJSONObject("citation"))
                        }
                        "input_json_delta" -> inputs.getOrPut(index) { StringBuilder() }.append(delta.getString("partial_json"))
                    }
                }
                "content_block_stop" -> {
                    val index = item.getInt("index")
                    check(openBlocks.remove(index)) { "the model closed an unknown content block" }
                    inputs.remove(index)?.let { blocks.getValue(index).put("input", JSONObject(it.toString())) }
                }
                "message_delta" -> {
                    val delta = item.getJSONObject("delta")
                    for (key in delta.keys()) message.put(key, delta.get(key))
                }
                "message_stop" -> {
                    done = true
                    return@events true
                }
            }
            false
        }
        check(started && done && openBlocks.isEmpty() && !message.isNull("stop_reason")) {
            "the model stream ended before completion"
        }
        return message.put("content", JSONArray(blocks.values.toList()))
    }

    private fun streamError(data: String): String = "the model stream failed: " +
        runCatching { JSONObject(data).getJSONObject("error").getString("message") }.getOrDefault(data.take(200))

    private fun events(reader: Reader, checkCancelled: () -> Unit, event: (String, String) -> Boolean) {
        val lines = reader.buffered()
        var type = ""
        val data = ArrayList<String>()
        while (true) {
            checkCancelled()
            val line = lines.readLine() ?: break
            if (line.isEmpty()) {
                if (data.isNotEmpty() && event(type, data.joinToString("\n"))) return
                type = ""
                data.clear()
            } else if (!line.startsWith(":")) {
                val field = line.substringBefore(':')
                val value = line.substringAfter(':', "").removePrefix(" ")
                when (field) {
                    "event" -> type = value
                    "data" -> data += value
                }
            }
        }
        if (data.isNotEmpty()) event(type, data.joinToString("\n"))
    }
}

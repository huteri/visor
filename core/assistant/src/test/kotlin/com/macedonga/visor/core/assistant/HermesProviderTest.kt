package com.macedonga.visor.core.assistant

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HermesProviderTest {
    @Test fun genericServerNeverProbesOrCapturesHermes() = runBlocking {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            paths += e.requestURI.path
            val body = e.requestBody.reader().readText()
            if (body.isNotEmpty()) bodies += JSONObject(body)
            e.responseHeaders.set("X-Hermes-Session-Id", "must-ignore")
            val text = if (e.requestURI.path == "/capabilities") """{"features":{"run_status":true,"session_continuity_header":"X-Hermes-Session-Id"}}"""
                else """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""
            val bytes = text.toByteArray(); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        val dir = Files.createTempDirectory("provider-isolation").toFile()
        try {
            val c = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "key")
            val diary = Diary(dir)
            repeat(2) { withContext(ConversationContext(diary, "c", replay = it == 1)) { assertEquals("ok", Brain({ c }).answer("question", Locale.ENGLISH).text) } }
            assertEquals(listOf("/chat/completions", "/chat/completions"), paths)
            assertTrue(bodies.all { it.has("tools") })
            assertFalse(java.io.File(dir, "sessions.json").exists())
        } finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test fun hermesChatHasNoLocalToolsOrRetriesAndUsesServerDefault() = runBlocking {
        val bodies = mutableListOf<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            val text = if (e.requestURI.path == "/capabilities") "{}" else {
                bodies += JSONObject(e.requestBody.reader().readText())
                """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""
            }
            val bytes = text.toByteArray(); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        val dir = Files.createTempDirectory("explicit-hermes").toFile()
        try {
            val c = AssistantConfig(provider = Provider.HERMES, baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "key", webSearch = true)
            val brain = Brain({ c }, appTools = AppTools { error("Hermes must not enumerate local apps") })
            val diary = Diary(dir)
            withContext(ConversationContext(diary, "c")) { assertEquals("ok", brain.answer("question", Locale.ENGLISH, canEnd = true).text) }
            val body = bodies.single()
            assertFalse(body.has("model"))
            assertFalse(body.has("tools"))
            assertFalse(body.has("reasoning_effort"))
            val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(system.contains("Tools are managed by Hermes"))
            assertFalse(system.contains("remember tool"))
            assertFalse(system.contains("end_conversation"))
            assertTrue(runCatching { withContext(ConversationContext(diary, "c", replay = true)) { brain.answer("question", Locale.ENGLISH) } }.isFailure)
            assertEquals(1, bodies.size)
        } finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test fun legacyDisabledApprovalPreferenceCannotBlockHermesRun() = runBlocking {
        val paths = mutableListOf<String>()
        val approvals = mutableListOf<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            paths += e.requestURI.path
            val body = e.requestBody.reader().readText()
            val text = when (e.requestURI.path) {
                "/capabilities" -> """{"features":{"run_status":true}}"""
                "/runs" -> """{"run_id":"owned"}"""
                "/runs/owned/approval" -> { approvals += JSONObject(body); "{}" }
                "/runs/owned" -> if (approvals.isEmpty())
                    """{"status":"waiting_for_approval","approval":{"run_id":"owned","request_id":"one","choices":["once","session","always"]}}"""
                    else """{"status":"completed","output":"ok"}"""
                else -> error("Unexpected HTTP path")
            }
            val bytes = text.toByteArray(); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        val dir = Files.createTempDirectory("legacy-hermes-approval").toFile()
        try {
            val values = mapOf<String, Any>("provider" to Provider.HERMES.name,
                "base_url" to "http://127.0.0.1:${server.address.port}", "api_key" to "key", "hermes_auto_approve" to false)
            val prefs = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader,
                arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getString", "getStringSet", "getBoolean", "getInt" -> values[args!![0]] ?: args[1]
                    else -> error(method.name)
                }
            } as android.content.SharedPreferences
            val context = org.mockito.Mockito.mock(android.content.Context::class.java)
            org.mockito.Mockito.`when`(context.getSharedPreferences("assistant", android.content.Context.MODE_PRIVATE)).thenReturn(prefs)
            val store = AssistantConfigStore(context)
            withContext(ConversationContext(Diary(dir), "c")) {
                assertEquals("ok", Brain({ store.config.value }).answer("question", Locale.ENGLISH).text)
            }
            assertEquals(1, paths.count { it == "/runs" })
            assertEquals(setOf("request_id", "choice"), approvals.single().keys().asSequence().toSet())
            assertEquals("one", approvals.single().getString("request_id"))
            assertEquals("once", approvals.single().getString("choice"))
        } finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test fun genericParserIgnoresHermesMetadata() {
        val text = "data: {\"hermes\":{\"failed\":true},\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        assertEquals("ok", ModelResponse.read(text.reader(), false).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
    }

    @Test fun explicitSwitchPreservesConfigAndKeepsProviderSlotsSeparate() {
        val values = mutableMapOf<String, Any?>()
        val prefs = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader, arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.Editor::class.java.classLoader, arrayOf(android.content.SharedPreferences.Editor::class.java)) { proxy, edit, a ->
                    when {
                        edit.name.startsWith("put") -> { values[a!![0] as String] = a[1]; proxy }
                        edit.name == "remove" -> { values.remove(a!![0]); proxy }
                        edit.name == "apply" -> null
                        edit.name == "commit" -> true
                        else -> error(edit.name)
                    }
                }
                "getString", "getStringSet", "getBoolean", "getInt" -> values[args!![0]] ?: args[1]
                else -> error(method.name)
            }
        } as android.content.SharedPreferences
        val context = org.mockito.Mockito.mock(android.content.Context::class.java)
        org.mockito.Mockito.`when`(context.getSharedPreferences("assistant", android.content.Context.MODE_PRIVATE)).thenReturn(prefs)
        val store = AssistantConfigStore(context)
        store.update { it.copy(provider = Provider.OPENAI_COMPATIBLE, baseUrl = "https://old.example/v1", apiKey = "old-key", model = "old-model") }
        store.switchProvider(Provider.HERMES)
        assertEquals("https://old.example/v1", store.config.value.api)
        assertEquals("old-key", store.config.value.apiKey)
        assertEquals("old-model", store.config.value.model)
        store.update { it.copy(baseUrl = "https://new.example/v1", apiKey = "new-key", model = "new-model") }
        store.switchProvider(Provider.OPENAI_COMPATIBLE)
        assertEquals("https://old.example/v1", store.config.value.api)
        assertEquals("old-key", store.config.value.apiKey)
        store.switchProvider(Provider.CLAUDE)
        store.switchProvider(Provider.HERMES)
        assertEquals("https://new.example/v1", store.config.value.api)
        assertEquals("new-key", store.config.value.apiKey)
        assertEquals("new-model", AssistantConfigStore(context).config.value.model)
        assertEquals(Provider.HERMES, AssistantConfigStore(context).config.value.provider)
    }

    @Test fun legacyGenericSlotKeepsUrlAndKeyAfterHermesEditAndReload() {
        val values = mutableMapOf<String, Any?>(
            "provider" to Provider.CLAUDE.name,
            "base_url" to "https://old.example/v1",
            "api_key" to "claude-key",
            "provider_slots" to """{"OPENAI_COMPATIBLE":{"key":"old-key","model":"old-model","lines":"old-lines"}}""",
        )
        val prefs = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader, arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.Editor::class.java.classLoader, arrayOf(android.content.SharedPreferences.Editor::class.java)) { proxy, edit, a ->
                    when {
                        edit.name.startsWith("put") -> { values[a!![0] as String] = a[1]; proxy }
                        edit.name == "remove" -> { values.remove(a!![0]); proxy }
                        edit.name == "apply" -> null
                        edit.name == "commit" -> true
                        else -> error(edit.name)
                    }
                }
                "getString", "getStringSet", "getBoolean", "getInt" -> values[args!![0]] ?: args[1]
                else -> error(method.name)
            }
        } as android.content.SharedPreferences
        val context = org.mockito.Mockito.mock(android.content.Context::class.java)
        org.mockito.Mockito.`when`(context.getSharedPreferences("assistant", android.content.Context.MODE_PRIVATE)).thenReturn(prefs)
        val store = AssistantConfigStore(context)
        assertEquals(Provider.CLAUDE, store.config.value.provider)
        store.switchProvider(Provider.HERMES)
        store.update { it.copy(baseUrl = "https://new.example/v1", apiKey = "new-key") }
        val reloaded = AssistantConfigStore(context)
        assertEquals("https://new.example/v1", reloaded.config.value.api)
        assertEquals("new-key", reloaded.config.value.apiKey)
        reloaded.switchProvider(Provider.OPENAI_COMPATIBLE)
        assertEquals("https://old.example/v1", reloaded.config.value.api)
        assertEquals("old-key", reloaded.config.value.apiKey)
        val restored = AssistantConfigStore(context).config.value
        assertEquals(Provider.OPENAI_COMPATIBLE, restored.provider)
        assertEquals("https://old.example/v1", restored.api)
        assertEquals("old-key", restored.apiKey)
        assertEquals("old-model", restored.model)
        assertEquals("old-lines", restored.linesModel)
    }

    @Test fun hermesIsExplicitCustomEndpointWithoutRequiredKey() {
        val provider = Provider.valueOf("HERMES")
        val c = AssistantConfig(provider = provider, baseUrl = "http://localhost:8642/v1")
        assertEquals("Hermes", provider.title)
        assertEquals(c.baseUrl, c.api)
        assertTrue(c.hasModel)
        assertEquals("", c.answerModel)
        assertNull(Provider.at(c.api))
    }
}

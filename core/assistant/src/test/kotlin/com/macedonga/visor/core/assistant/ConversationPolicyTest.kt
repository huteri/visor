package com.macedonga.visor.core.assistant

import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

class ConversationPolicyTest {
    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let { it.isAccessible = true; it.get(null) as Unsafe }
    private fun set(o: Any, name: String, value: Any?) = o.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.set(o, value) }

    @Test fun liveResumePinsRecentConversationAndExplicitNewDoesNotMix() {
        val dir = Files.createTempDirectory("live-policy").toFile()
        try {
            val diary = Diary(dir)
            diary.record(TurnRecord("ordinary", 1_000, "earlier", answer = "answer", conversation = "recent"))
            assertEquals("recent", diary.liveConversationAt(301_000, 600_000, "session"))
            diary.record(TurnRecord("live", 301_000, "continue", answer = "answer", conversation = "recent", session = "session", source = TurnSource.LIVE_AI))
            diary.record(TurnRecord("phone", 302_000, "unrelated", answer = "answer", conversation = "other"))
            assertEquals("recent", diary.liveConversationAt(303_000, 600_000, "session"))
            assertNotEquals("recent", diary.liveConversationAt(303_000, 600_000, "session", newConversation = true))
            assertNotEquals("other", diary.liveConversationAt(1_000_000, 600_000, "new-session"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun historyAttachesOnlyMostRecentExistingPhotoAndHonorsVisionOff() {
        val dir = Files.createTempDirectory("reference-policy").toFile()
        try {
            val diary = Diary(dir)
            val first = java.io.File(diary.photos, "first.jpg").apply { writeBytes(byteArrayOf(1)) }
            val last = java.io.File(diary.photos, "last.jpg").apply { writeBytes(byteArrayOf(2)) }
            diary.record(TurnRecord("one", 1_000, "first", answer = "one", conversation = "c", image = first.path))
            diary.record(TurnRecord("two", 2_000, "second", answer = "two", conversation = "c", image = last.path))
            diary.record(TurnRecord("now", 3_000, "now", conversation = "c"))
            val assistant = unsafe.allocateInstance(Assistant::class.java) as Assistant
            val store = unsafe.allocateInstance(AssistantConfigStore::class.java) as AssistantConfigStore
            val config = MutableStateFlow(AssistantConfig(vision = true))
            set(store, "config", config); set(assistant, "store", store); set(assistant, "diary", diary)
            @Suppress("UNCHECKED_CAST")
            fun history() = assistant.javaClass.getDeclaredMethod("pastOf", String::class.java).let { it.isAccessible = true; it.invoke(assistant, "now") as List<Exchange> }
            assertEquals(1, history().count { it.image != null })
            assertArrayEquals(byteArrayOf(2), history().last().image)
            config.value = config.value.copy(vision = false)
            assertTrue(history().all { it.image == null })
            config.value = config.value.copy(vision = true)
            last.delete()
            assertTrue(history().all { it.image == null })
        } finally { dir.deleteRecursively() }
    }

    @Test fun hermesWakesRetainDurableConversationUntilExplicitResetWithoutLeakingToOtherProviders() {
        val dir = Files.createTempDirectory("hermes-conversation-policy").toFile()
        try {
            val assistant = unsafe.allocateInstance(Assistant::class.java) as Assistant
            val store = unsafe.allocateInstance(AssistantConfigStore::class.java) as AssistantConfigStore
            val config = MutableStateFlow(AssistantConfig(provider = Provider.HERMES, baseUrl = "http://localhost:1234", apiKey = "key"))
            set(store, "config", config); set(assistant, "store", store)
            var diary = Diary(dir)
            set(assistant, "diary", diary)
            fun record(id: String, at: Long, question: String = "continue", conversation: String = "") {
                assistant.javaClass.getDeclaredMethod("record", TurnRecord::class.java).let {
                    it.isAccessible = true; it.invoke(assistant, TurnRecord(id, at, question, conversation = conversation))
                }
            }
            record("first", 1_000)
            val kept = diary.turn("first")!!.conversation
            diary = Diary(dir); set(assistant, "diary", diary)
            assertEquals(kept, assistant.openConversation())
            record("late", 1_000 + 3 * 86_400_000L)
            assertEquals(kept, diary.turn("late")!!.conversation)
            config.value = config.value.copy(provider = Provider.OPENAI)
            record("other", 1_000 + 6 * 86_400_000L)
            assertNotEquals(kept, diary.turn("other")!!.conversation)
            config.value = config.value.copy(provider = Provider.HERMES)
            record("returned", 1_000 + 9 * 86_400_000L)
            assertEquals(kept, diary.turn("returned")!!.conversation)
            config.value = config.value.copy(apiKey = "other-key")
            record("other-account", 1_000 + 10 * 86_400_000L)
            assertNotEquals(kept, diary.turn("other-account")!!.conversation)
            config.value = config.value.copy(apiKey = "key")
            record("reset", 1_000 + 11 * 86_400_000L, "new conversation")
            val reset = diary.turn("reset")!!.conversation
            assertNotEquals(kept, reset)
            diary = Diary(dir); set(assistant, "diary", diary)
            record("after-reset", 1_000 + 14 * 86_400_000L)
            assertEquals(reset, diary.turn("after-reset")!!.conversation)
            record("explicit", 1_000 + 15 * 86_400_000L, conversation = "chosen-chat")
            assertEquals("chosen-chat", assistant.openConversation())
            assertEquals(10, AssistantConfig().memoryMinutes)
        } finally { dir.deleteRecursively() }
    }

    @Test fun completedRunChurnPreservesOrdinaryWakeAndBackendHistoryAfterRecreation() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<String?>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/capabilities") { e ->
            val data = """{"features":{"session_continuity_header":"X-Hermes-Session-Id"}}""".toByteArray()
            e.sendResponseHeaders(200, data.size.toLong()); e.responseBody.use { it.write(data) }
        }
        server.createContext("/api/sessions/kept-history") { e -> e.sendResponseHeaders(200, -1); e.close() }
        server.createContext("/chat/completions") { e ->
            e.requestBody.readBytes()
            requests += e.requestHeaders.getFirst("X-Hermes-Session-Id")
            e.responseHeaders.set("X-Hermes-Session-Id", "kept-history")
            val data = """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""".toByteArray()
            e.sendResponseHeaders(200, data.size.toLong()); e.responseBody.use { it.write(data) }
        }
        server.start()
        val dir = Files.createTempDirectory("hermes-churn-policy").toFile()
        try {
            val assistant = unsafe.allocateInstance(Assistant::class.java) as Assistant
            val store = unsafe.allocateInstance(AssistantConfigStore::class.java) as AssistantConfigStore
            val config = AssistantConfig(provider = Provider.HERMES, baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "key")
            set(store, "config", MutableStateFlow(config)); set(assistant, "store", store)
            var diary = Diary(dir)
            set(assistant, "diary", diary)
            fun wake(id: String, at: Long): String {
                assistant.javaClass.getDeclaredMethod("record", TurnRecord::class.java).let {
                    it.isAccessible = true; it.invoke(assistant, TurnRecord(id, at, "continue"))
                }
                return diary.turn(id)!!.conversation
            }
            val kept = wake("first", 1_000)
            kotlinx.coroutines.withContext(ConversationContext(diary, kept)) { Brain({ config }).answer("first", java.util.Locale.ENGLISH) }
            val backendScope = org.json.JSONObject(java.io.File(dir, "sessions.json").readText()).keys().asSequence()
                .single { !it.startsWith("hermes-conversation:") }
            repeat(140) { diary.keepSession("runs:completed-$it.run", "") }
            diary = Diary(dir); set(assistant, "diary", diary)
            assertEquals("kept-history", diary.session(backendScope))
            assertEquals(kept, assistant.openConversation())
            assertEquals(kept, wake("late", 3 * 86_400_000L))
            kotlinx.coroutines.withContext(ConversationContext(diary, diary.turn("late")!!.conversation)) {
                Brain({ config }).answer("follow-up", java.util.Locale.ENGLISH)
            }
            assertEquals(listOf(null, "kept-history"), requests)
            repeat(140) { assertTrue(org.json.JSONObject(java.io.File(dir, "sessions.json").readText()).has("runs:completed-$it.run")) }
        } finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test fun voiceOnlyLiveContextNeverResumesImageBearingServerSession() {
        val dir = Files.createTempDirectory("hermes-live-privacy").toFile()
        try {
            val assistant = unsafe.allocateInstance(Assistant::class.java) as Assistant
            val store = unsafe.allocateInstance(AssistantConfigStore::class.java) as AssistantConfigStore
            set(store, "config", MutableStateFlow(AssistantConfig(provider = Provider.HERMES)))
            set(assistant, "store", store)
            var diary = Diary(dir)
            diary.record(TurnRecord("photo", 1_000, "look", answer = "image answer", image = "photo.jpg", conversation = "camera-chat"))
            diary.record(TurnRecord("voice", 200_000_000, "continue", source = TurnSource.LIVE_AI, conversation = "camera-chat"))
            set(assistant, "diary", diary)
            val live = org.mockito.Mockito.mock(LiveAi::class.java)
            set(assistant, "liveAi", live)
            fun context(id: String) = assistant.javaClass.getDeclaredMethod("contextOf", String::class.java, Boolean::class.javaPrimitiveType).let {
                it.isAccessible = true; it.invoke(assistant, id, false) as ConversationContext
            }
            org.mockito.Mockito.`when`(live.imageAllowed).thenReturn(true)
            val camera = context("photo").conversation
            org.mockito.Mockito.`when`(live.imageAllowed).thenReturn(false)
            val voice = context("voice").conversation
            assertNotEquals(camera, voice)
            diary.keepSession(camera, "image-bearing-server-session")
            assertNull(diary.session(voice))
            @Suppress("UNCHECKED_CAST")
            val past = assistant.javaClass.getDeclaredMethod("pastOf", String::class.java).let {
                it.isAccessible = true; it.invoke(assistant, "voice") as List<Exchange>
            }
            assertTrue(past.all { it.image == null })
        } finally { dir.deleteRecursively() }
    }

    @Test fun freshWakeHonorsConfiguredTenMinuteWindow() {
        val dir = Files.createTempDirectory("conversation-policy").toFile()
        try {
            val diary = Diary(dir)
            diary.record(TurnRecord("old", 1_000, "earlier", answer = "answer", conversation = "kept"))
            val assistant = unsafe.allocateInstance(Assistant::class.java) as Assistant
            val store = unsafe.allocateInstance(AssistantConfigStore::class.java) as AssistantConfigStore
            set(store, "config", MutableStateFlow(AssistantConfig(memoryMinutes = 10)))
            set(assistant, "store", store); set(assistant, "diary", diary)
            val linkType = Class.forName("com.macedonga.visor.core.assistant.Assistant\$Link")
            val link = linkType.declaredConstructors.single().let { it.isAccessible = true; it.newInstance(assistant, org.mockito.Mockito.mock(com.macedonga.visor.core.session.DataXConnection::class.java)) }
            @Suppress("UNCHECKED_CAST")
            val fresh = linkType.getDeclaredField("fresh").let { it.isAccessible = true; it.get(link) as MutableSet<String> }
            fresh.add("new"); set(assistant, "link", link)
            assistant.javaClass.getDeclaredMethod("record", TurnRecord::class.java).let { it.isAccessible = true; it.invoke(assistant, TurnRecord("new", 301_000, "continue")) }
            assertEquals("kept", diary.turn("new")!!.conversation)
            assertNotEquals("kept", diary.conversationAt(1_000_000, 600_000))
        } finally { dir.deleteRecursively() }
    }
}

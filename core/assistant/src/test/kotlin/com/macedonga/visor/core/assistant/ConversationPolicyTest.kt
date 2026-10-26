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
            val link = linkType.declaredConstructors.single().let { it.isAccessible = true; it.newInstance(org.mockito.Mockito.mock(com.macedonga.visor.core.session.DataXConnection::class.java)) }
            @Suppress("UNCHECKED_CAST")
            val fresh = linkType.getDeclaredField("fresh").let { it.isAccessible = true; it.get(link) as MutableSet<String> }
            fresh.add("new"); set(assistant, "link", link)
            assistant.javaClass.getDeclaredMethod("record", TurnRecord::class.java).let { it.isAccessible = true; it.invoke(assistant, TurnRecord("new", 301_000, "continue")) }
            assertEquals("kept", diary.turn("new")!!.conversation)
            assertNotEquals("kept", diary.conversationAt(1_000_000, 600_000))
        } finally { dir.deleteRecursively() }
    }
}

package com.macedonga.visor.core.assistant

import android.content.Context
import com.macedonga.visor.core.protocol.Thrift
import com.macedonga.visor.core.protocol.ThriftWriter
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.LinkState
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.intrinsics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import sun.misc.Unsafe

/** Exercises real Assistant answer/listen adapters; Android IO alone is replaced. */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantProgressOwnershipTest {
    private lateinit var clock: org.mockito.MockedStatic<android.os.SystemClock>
    @org.junit.Before fun clock() { clock = mockStatic(android.os.SystemClock::class.java); clock.`when`<Long> { android.os.SystemClock.elapsedRealtime() }.thenReturn(0L) }
    @org.junit.After fun closeClock() { clock.close() }
    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let { it.isAccessible = true; it.get(null) as Unsafe }
    private fun put(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.set(target, value) }
    private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(target) }
    private suspend fun call(target: Any, name: String, vararg args: Any?): Any? = suspendCoroutineUninterceptedOrReturn { continuation ->
        target.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size + 1 }.let {
            it.isAccessible = true
            try { it.invoke(target, *args, continuation) } catch (e: java.lang.reflect.InvocationTargetException) { throw e.cause!! }
        }
    }
    private inner class Fixture(val test: TestScope, enabled: Boolean = true) {
        val assistant = unsafe.allocateInstance(Assistant::class.java) as Assistant
        val brain = mock(Brain::class.java)
        val voice = mock(Voice::class.java)
        val diary = mock(Diary::class.java)
        val config = MutableStateFlow(AssistantConfig(spokenProgress = enabled, vision = true, glassesVoice = false))
        val generation = AtomicLong()
        val store = unsafe.allocateInstance(AssistantConfigStore::class.java) as AssistantConfigStore
        val mux = mock(DataXConnection::class.java)
        val link = Class.forName("com.macedonga.visor.core.assistant.Assistant\$Link").declaredConstructors.single().let { it.isAccessible = true; it.newInstance(mux) }
        var deviceVoice = "voice-one"
        var prepare: suspend () -> ByteArray? = { null }
        val writes: Int get() = mockingDetails(mux).invocations.size
        init {
            put(store, "_config", config); put(store, "config", config); put(store, "generation", generation)
            val connection = mock(GlassesConnection::class.java)
            `when`(connection.state).thenReturn(MutableStateFlow(GlassesState(link = LinkState.READY)))
            `when`(diary.conversationAt(anyLong(), anyLong())).thenReturn("conversation")
            `when`(diary.turns).thenReturn(MutableStateFlow(emptyList()))
            `when`(diary.photos).thenReturn(java.io.File(System.getProperty("java.io.tmpdir"), "assistant-test-photos"))
            put(assistant, "connection", connection); put(assistant, "brain", brain); put(assistant, "voice", voice)
            put(assistant, "store", store); put(assistant, "diary", diary); put(assistant, "scope", test.backgroundScope)
            put(assistant, "_phase", MutableStateFlow(AssistantPhase.LISTENING)); put(assistant, "_notes", MutableSharedFlow<String>(extraBufferCapacity = 64))
            put(assistant, "progressTurn", AtomicLong()); put(assistant, "link", link); put(assistant, "liveAi", mock(LiveAi::class.java))
            put(assistant, "glassesVoice", { deviceVoice }); put(assistant, "processingTimeoutS", { 55L })
            put(assistant, "liveFrame", suspend { prepare() })
            put(link, "m0", 1); put(link, "s0", 2); put(link, "listening", "parent")
        }
        @Suppress("UNCHECKED_CAST") fun <T> state(name: String) = get(link, name) as T
        suspend fun answer(id: String = "child") {
            val visual = Class.forName("com.macedonga.visor.core.assistant.Visual").declaredConstructors.first { it.parameterCount == 5 }.let { it.isAccessible = true; it.newInstance(null, "", "", false, "") }
            call(assistant, "answer", link, id, "question", visual)
        }
        fun closeParent() {
            // Exercise upstream ev59 handler, not merely progress controller's closed-set predicate.
            assistant.javaClass.getDeclaredMethod("closed", link.javaClass, ByteArray::class.java).let {
                it.isAccessible = true
                it.invoke(assistant, link, ThriftWriter().str(1, "parent").done())
            }
        }
        fun associate() { state<MutableMap<String, String>>("sessionOf")["child"] = "parent" }
    }

    @Test fun `local followup inherits parent stream and remote close cancels pending model`() = runTest {
        val f = Fixture(this)
        f.state<MutableSet<String>>("answered").add("parent")
        f.state<MutableMap<String, String>>("streamIds")["parent"] = "parent-stream"
        var canceled = false
        // Suspend model through Mockito's continuation rather than replacing Assistant behavior.
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST") val continuation = invocation.rawArguments.last() as kotlin.coroutines.Continuation<Reply>
            val model: suspend () -> Reply = { try { delay(70_000); Reply("late") } finally { canceled = true } }
            model.startCoroutineUninterceptedOrReturn(continuation)
        }.`when`(f.brain).answer(anyString(), any<Locale>(), any<List<Exchange>>(), anyOrNull<ByteArray>(), anyString(), any<Audience>())
        var utterance: ((String, Long, Long) -> Unit)? = null
        mockConstruction(GlassesEars::class.java) { _, context ->
            @Suppress("UNCHECKED_CAST")
            utterance = context.arguments()[3] as (String, Long, Long) -> Unit
        }.use {
            f.assistant.javaClass.getDeclaredMethod("listen", f.link.javaClass, String::class.java).let { method -> method.isAccessible = true; method.invoke(f.assistant, f.link, "parent") }
            utterance!!("followup", 10, 20)
            advanceTimeBy(2_000); runCurrent()
            val child = f.state<MutableSet<String>>("taken").single()
            assertEquals("parent", f.state<Map<String, String>>("sessionOf")[child])
            f.closeParent()
            advanceTimeBy(100); runCurrent()
            assertTrue("Parent close must cancel pending model", canceled)
            val count = f.writes
            advanceTimeBy(50_000); runCurrent()
            assertEquals(count, f.writes)
        }
    }

    private fun nativeHandoff(simplified: Boolean, closeAfter: Int? = null, glasses: Boolean = true, text: String = "native final") = runTest {
        clock.`when`<Long> { android.os.SystemClock.elapsedRealtime() }.thenAnswer { testScheduler.currentTime }
        val f = Fixture(this); f.associate()
        f.config.value = f.config.value.copy(glassesVoice = glasses, vision = false)
        put(f.link, "simplified", simplified)
        f.state<MutableMap<String, String>>("streamIds")["parent"] = "parent-stream"
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST") val continuation = invocation.rawArguments.last() as kotlin.coroutines.Continuation<Reply>
            val model: suspend () -> Reply = { delay(41_000); Reply(text) }
            model.startCoroutineUninterceptedOrReturn(continuation)
        }.`when`(f.brain).answer(anyString(), any<Locale>(), any<List<Exchange>>(), anyOrNull<ByteArray>(), anyString(), any<Audience>())
        `when`(f.voice.progressPcm(anyString(), any<Locale>())).thenReturn(ShortArray(960))
        `when`(f.voice.progressEncoder()).thenReturn(object : ProgressEncoder {
            override fun push(pcm: ShortArray, end: Boolean) = listOf(OpusPacket(byteArrayOf(if (end) 9 else 0), 20_000))
            override fun close() = Unit
        })
        val events = mutableListOf<Int>()
        doAnswer { invocation ->
            val type = invocation.getArgument<Int?>(2)
            val event = if (type == MT_NET_EVENT || type == 0) parseNetEvent(invocation.getArgument(1)).id else type ?: -1
            events += event
            if (event == closeAfter && MT_STREAM_END in events) f.closeParent()
            Unit
        }.`when`(f.mux).send(anyInt(), any<ByteArray>(), anyOrNull<Int>(), anyOrNull<Int>())
        f.answer()
        verify(f.voice, never()).speak(anyString(), any<Locale>())
        verify(f.voice, times(if (glasses) 1 else 2)).progressPcm(anyString(), any<Locale>())
        val end = events.indexOf(MT_STREAM_END)
        assertTrue("stream ended", end >= 0)
        if (closeAfter != null) {
            assertFalse("no native completion after close", events.contains(EV_MWA_VOICE_INTERACTION_COMPLETE))
            assertEquals(closeAfter, events.last())
        } else if (!glasses) {
            assertFalse(events.contains(EV_MWA_VOICE_INTERACTION_COMPLETE))
            assertEquals(listOf(EV_TTS_END_INFO, EV_KEEP_AIR_SESSION_ALIVE), events.drop(end + 1))
        } else {
            assertEquals(listOf(EV_TTS_END_INFO, EV_START_OF_USER_SPEECH, EV_FINAL_TRANSCRIPTION, EV_ON_PHONE_UNDERSTANDING) +
                (if (simplified) listOf(EV_VOICE_ACTION_RESPONSE, EV_MWA_VOICE_INTERACTION_COMPLETE) else listOf(EV_MWA_VOICE_INTERACTION_COMPLETE, EV_VOICE_ACTION_RESPONSE)) + EV_KEEP_AIR_SESSION_ALIVE,
                events.drop(end + 1))
            val completions = mockingDetails(f.mux).invocations.filter { it.method.name == "send" && it.arguments[2] == MT_NET_EVENT }
                .map { parseNetEvent(it.arguments[1] as ByteArray) }.filter { it.id == EV_MWA_VOICE_INTERACTION_COMPLETE }
            assertTrue(String(completions.single().body).contains("child"))
            val fulfillment = Thrift.loads(completions.single().body)[4] as Map<*, *>
            assertEquals(text.endsWith("?"), fulfillment[3])
        }
    }
    @Test fun `native handoff simplified preserves ordinary framing after audio end`() = nativeHandoff(true)
    @Test fun `native handoff full mode preserves ordinary framing after audio end`() = nativeHandoff(false)
    @Test fun `native progress final question preserves upstream followup simplified`() = nativeHandoff(true, text = "Remember that?")
    @Test fun `native progress final question preserves upstream followup full mode`() = nativeHandoff(false, text = "Remember that?")
    @Test fun `remote close at stream end prevents timing and native writes`() = nativeHandoff(true, MT_STREAM_END)
    @Test fun `remote close at timing prevents native writes`() = nativeHandoff(false, EV_TTS_END_INFO)
    @Test fun `remote close during native preamble prevents completion`() = nativeHandoff(true, EV_START_OF_USER_SPEECH)
    @Test fun `saved phone voice keeps streamed final`() = nativeHandoff(true, glasses = false)
    @Test fun `fast selected glasses voice never synthesizes phone audio`() = runTest {
        val f = Fixture(this)
        f.config.value = f.config.value.copy(glassesVoice = true, vision = false)
        `when`(f.brain.answer(anyString(), any<Locale>(), any<List<Exchange>>(), anyOrNull<ByteArray>(), anyString(), any<Audience>())).thenReturn(Reply("fast"))
        f.answer()
        verifyNoInteractions(f.voice)
        val events = mockingDetails(f.mux).invocations.filter { it.method.name == "send" && it.arguments[2] == MT_NET_EVENT }
            .map { parseNetEvent(it.arguments[1] as ByteArray).id }
        assertTrue(events.contains(EV_MWA_VOICE_INTERACTION_COMPLETE))
        assertFalse(events.contains(EV_TTS_END_INFO))
    }

    private fun preparationRevocation(change: Fixture.() -> Unit) = runTest {
        val f = Fixture(this); f.associate()
        f.prepare = { delay(100); f.change(); null }
        `when`(f.brain.answer(anyString(), any<Locale>(), any<List<Exchange>>(), anyOrNull<ByteArray>(), anyString(), any<Audience>())).thenReturn(Reply("stale"))
        `when`(f.voice.speak(anyString(), any<Locale>())).thenReturn(emptyList())
        f.answer()
        verifyNoInteractions(f.brain)
        verifyNoInteractions(f.voice)
        assertEquals(0, f.writes)
    }
    @Test fun `toggle off during image preparation revokes instead of baseline delivery`() = preparationRevocation {
        generation.incrementAndGet(); config.value = config.value.copy(spokenProgress = false)
    }
    @Test fun `config changed then restored during image preparation still revokes`() = preparationRevocation { generation.addAndGet(2) }
    @Test fun `device voice change during image preparation revokes`() = preparationRevocation { deviceVoice = "voice-two" }
    @Test fun `parent remote close during image preparation revokes`() = preparationRevocation { associate(); closeParent() }
    @Test fun `turn begun off keeps baseline when enabled during preparation`() = runTest {
        val f = Fixture(this, enabled = false)
        f.prepare = { delay(100); f.generation.incrementAndGet(); f.config.value = f.config.value.copy(spokenProgress = true); null }
        `when`(f.brain.answer(anyString(), any<Locale>(), any<List<Exchange>>(), anyOrNull<ByteArray>(), anyString(), any<Audience>())).thenReturn(Reply("baseline"))
        `when`(f.voice.speak(anyString(), any<Locale>())).thenReturn(emptyList())
        f.answer()
        verify(f.voice).speak(anyString(), any<Locale>())
        assertTrue(f.writes > 0)
    }

    @Test fun `parent close during normal final synthesis prevents all writes`() = runTest {
        val f = Fixture(this); f.associate()
        `when`(f.brain.answer(anyString(), any<Locale>(), any<List<Exchange>>(), anyOrNull<ByteArray>(), anyString(), any<Audience>())).thenReturn(Reply("ready"))
        var synthesisCanceled = false
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST") val continuation = invocation.rawArguments.last() as kotlin.coroutines.Continuation<List<OpusPacket>>
            val synthesis: suspend () -> List<OpusPacket> = {
                try { delay(1_000); emptyList() } finally { synthesisCanceled = true }
            }
            synthesis.startCoroutineUninterceptedOrReturn(continuation)
        }.`when`(f.voice).speak(anyString(), any<Locale>())
        backgroundScope.launch { delay(100); f.closeParent() }
        f.answer()
        verify(f.voice).speak(anyString(), any<Locale>())
        assertTrue(synthesisCanceled)
        assertTrue(testScheduler.currentTime < 1_000)
        assertEquals(0, f.writes)
    }
}

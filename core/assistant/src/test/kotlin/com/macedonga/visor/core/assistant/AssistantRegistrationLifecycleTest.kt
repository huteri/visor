package com.macedonga.visor.core.assistant

import android.content.Context
import com.macedonga.visor.core.connection.*
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import sun.misc.Unsafe
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Real registration, Wi-Fi host collector and assistant error collector; no revocation-helper calls. */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantRegistrationLifecycleTest {
    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
    private fun set(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private suspend fun call(target: Any, name: String, vararg args: Any?): Any? = suspendCoroutineUninterceptedOrReturn { continuation ->
        target.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(target, *args, continuation)
    }

    private suspend fun TestScope.registrationRevocation(disable: Boolean, releaseAnswer: Boolean) = run {
        val assistant = unsafe.allocateInstance(Assistant::class.java) as Assistant
        val config = MutableStateFlow(AssistantConfig())
        val generation = java.util.concurrent.atomic.AtomicLong()
        val store = mock(AssistantConfigStore::class.java)
        `when`(store.config).thenReturn(config)
        `when`(store.generation).thenReturn(generation)
        set(assistant, "store", store)
        set(assistant, "scope", backgroundScope)
        set(assistant, "_notes", MutableSharedFlow<String>(extraBufferCapacity = 64))
        set(assistant, "_phase", MutableStateFlow(AssistantPhase.REGISTERING))
        set(assistant, "progressTurn", java.util.concurrent.atomic.AtomicLong())
        val live = LiveAi(mock(Context::class.java), backgroundScope, mock(LiveAiHost::class.java), { 0L })
        set(assistant, "liveAi", live)
        val errors = MutableSharedFlow<ProtocolError>(extraBufferCapacity = 64)
        val connection = mock(GlassesConnection::class.java, CALLS_REAL_METHODS)
        doReturn(null).`when`(connection).pause(anyString())
        set(connection, "_protocolErrors", errors)
        set(connection, "protocolErrors", errors)
        set(connection, "_warnings", MutableSharedFlow<String>(extraBufferCapacity = 64))
        // Keep automatic Bluetooth retry out of this Wi-Fi fixture.
        doReturn(MutableStateFlow(GlassesState())).`when`(connection).state
        set(assistant, "connection", connection)
        assistant.watchProtocolErrors(connection.protocolErrors)
        val writes = mutableListOf<Int?>()
        var allocations = 0
        val mux = mock(DataXConnection::class.java) { invocation ->
            when (invocation.method.name) {
                "openChannel" -> { allocations++; 6 + allocations }
                "send" -> { writes.add(invocation.getArgument(2)); Unit }
                else -> RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
        val host = mock(SessionHost::class.java) { invocation ->
            when (invocation.method.name) {
                "getEvents" -> events
                "submit" -> { backgroundScope.launch { invocation.getArgument<suspend (DataXConnection) -> Unit>(0)(mux) }; null }
                else -> RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val stream = unsafe.allocateInstance(LiveStream::class.java) as LiveStream
        set(stream, "scope", backgroundScope)
        set(stream, "connection", connection)
        set(stream, "hosts", SessionHostFactory { _, _ -> host })
        set(stream, "_log", MutableStateFlow<List<String>>(emptyList()))
        set(stream, "_notes", MutableSharedFlow<String>(extraBufferCapacity = 64))
        set(stream, "_wifiSession", MutableStateFlow<DataXConnection?>(null))
        backgroundScope.launch { call(stream, "openWifiSession", GlassesTarget(GlassesLink.Wifi("127.0.0.1", 1234), "test"), mock(com.macedonga.visor.core.identity.IdentityBundle::class.java), "127.0.0.1", 1234, null) }
        runCurrent()
        backgroundScope.launch { events.emit(LinkStateChanged(LinkState.READY)) }
        runCurrent()
        val registration = backgroundScope.launch { call(assistant, "bringUp", mux) }
        runCurrent()
        val owner = get(assistant, "link")!!
        assertEquals(1, allocations)
        assertEquals(listOf(MT_INIT_MESSAGE_REQUEST), writes)
        if (disable) {
            config.value = config.value.copy(enabled = false)
            generation.incrementAndGet()
        } else {
            backgroundScope.launch { events.emit(ProtocolError("service 15 answered status 0xc002 on channel 0x0007", origin = mux)) }
            runCurrent()
            assertNull(get(assistant, "link"))
        }
        if (releaseAnswer) (get(owner, "m0Answered") as CompletableDeferred<Unit>).complete(Unit)
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals("Revoked registration must allocate no s0", 1, allocations)
        assertEquals("Revoked registration must send no stream/resources/announcement", listOf(MT_INIT_MESSAGE_REQUEST), writes)
        assertTrue(registration.isCompleted)

        // Old cleanup/errors must not clear or cancel a fresh registration.
        config.value = config.value.copy(enabled = true)
        generation.incrementAndGet()
        val replacement = backgroundScope.launch { call(assistant, "bringUp", mux) }
        runCurrent()
        val next = get(assistant, "link")!!
        assertNotSame(owner, next)
        backgroundScope.launch { events.emit(ProtocolError("service 15 answered status 0xc002 on channel 0x0007", origin = mux)) }
        runCurrent()
        (get(next, "m0Answered") as CompletableDeferred<Unit>).complete(Unit)
        runCurrent()
        (get(next, "s0Answered") as CompletableDeferred<Unit>).complete(Unit)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(replacement.isCompleted)
        assertSame(next, get(assistant, "link"))
        assertEquals(AssistantPhase.LISTENING, (get(assistant, "_phase") as MutableStateFlow<*>).value)
        assertEquals(3, allocations)
        assertEquals(4, writes.count { it == MT_NET_EVENT })
    }

    @Test fun wifiCloseDuringMessageRegistrationBlocksReleasedAnswer() = runTest { registrationRevocation(false, true) }
    @Test fun wifiCloseDuringMessageRegistrationBlocksTimeout() = runTest { registrationRevocation(false, false) }
    @Test fun disableDuringMessageRegistrationBlocksReleasedAnswer() = runTest { registrationRevocation(true, true) }
    @Test fun disableDuringMessageRegistrationBlocksTimeout() = runTest { registrationRevocation(true, false) }
}

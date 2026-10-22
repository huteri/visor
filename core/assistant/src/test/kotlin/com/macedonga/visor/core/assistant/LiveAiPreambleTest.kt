package com.macedonga.visor.core.assistant

import android.content.Context
import com.macedonga.visor.core.protocol.Thrift
import com.macedonga.visor.core.protocol.ThriftWriter
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.*
import com.macedonga.visor.core.connection.*
import com.macedonga.visor.core.media.LiveStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import sun.misc.Unsafe
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.startCoroutine

/** Real LiveAi heard -> Assistant host -> liveAnswer -> phone stream; ordinary native delivery unchanged.
 * Reflection bypasses Android-only constructor startup, not the delivery implementation.
 */
class LiveAiPreambleTest {
    private data class Sent(val type: Int?, val event: NetEvent)
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private suspend fun call(target: Any, name: String, vararg args: Any?): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = target.javaClass.declaredMethods.single { it.name == name }
            method.isAccessible = true
            // Optional per-delivery flag defaults false on ordinary calls.
            val supplied = args.toMutableList()
            while (supplied.size < method.parameterCount - 1) {
                supplied.add(if (method.parameterTypes[supplied.size] == Boolean::class.javaPrimitiveType) false else ({}))
            }
            method.invoke(target, *supplied.toTypedArray(), continuation)
        }

    private suspend fun sequence(simplified: Boolean, liveTurn: Boolean, voiceOnly: Boolean? = null, endDuringFrame: Boolean = false, jpeg: Boolean = false, revokeDuringFrame: Boolean = false, revokeDuringModel: Boolean = false, replaceDuringFrame: Boolean = false, replaceDuringModel: Boolean = false, stopAfter: Int? = null, expectPhone: Boolean = liveTurn, stopDuringSynthesis: Boolean = false, disableDuringModel: Boolean = false, closeDuringSynthesis: Boolean = false, staleCloseDuringSynthesis: Boolean = false, closeDuringModel: Boolean = false) {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val assistant = unsafe.allocateInstance(Assistant::class.java) as Assistant
        val context = mock(Context::class.java)
        val store = mock(AssistantConfigStore::class.java)
        `when`(store.generation).thenReturn(java.util.concurrent.atomic.AtomicLong())
        `when`(store.config).thenReturn(MutableStateFlow(AssistantConfig(glassesVoice = true)))
        val synthesisEntered = CompletableDeferred<Unit>()
        val synthesisRelease = CompletableDeferred<Unit>()
        val voice = mock(Voice::class.java) { invocation ->
            if (invocation.method.name == "speak") {
                val packets = listOf(OpusPacket(byteArrayOf(1, 2), 20_000), OpusPacket(byteArrayOf(3), 20_000), OpusPacket(byteArrayOf(4), 20_000))
                if (stopDuringSynthesis || closeDuringSynthesis || staleCloseDuringSynthesis) {
                    val continuation = invocation.rawArguments.last() as kotlin.coroutines.Continuation<List<OpusPacket>>
                    val synthesis: suspend () -> List<OpusPacket> = {
                        synthesisEntered.complete(Unit)
                        withContext(NonCancellable) { synthesisRelease.await() }
                        packets
                    }
                    synthesis.startCoroutine(continuation)
                    kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
                } else packets
            }
            else RETURNS_DEFAULTS.answer(invocation)
        }
        set(assistant, "voice", voice)
        var modelImage: ByteArray? = null
        val modelEntered = CompletableDeferred<Unit>()
        val modelRelease = CompletableDeferred<Unit>()
        var dispatched = false
        val brain = mock(Brain::class.java) { invocation ->
            if (invocation.method.name == "answer") {
                modelImage = invocation.getArgument(3)
                if (revokeDuringModel || replaceDuringModel || disableDuringModel || closeDuringModel) {
                    val continuation = invocation.rawArguments.last() as kotlin.coroutines.Continuation<Reply>
                    val preparation: suspend () -> Reply = {
                        modelEntered.complete(Unit)
                        modelRelease.await()
                        kotlin.coroutines.coroutineContext[LiveImageLease]?.validate()
                        dispatched = true
                        Reply("Native reply.")
                    }
                    preparation.startCoroutine(continuation)
                    kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
                } else Reply("Native reply.")
            } else RETURNS_DEFAULTS.answer(invocation)
        }
        set(assistant, "context", context)
        set(assistant, "store", store)
        set(assistant, "brain", brain)
        val diary = mock(Diary::class.java)
        val directory = java.nio.file.Files.createTempDirectory("visor-jpeg-test-").toFile()
        `when`(diary.photos).thenReturn(directory)
        set(assistant, "diary", diary)
        var setting = voiceOnly
        val jpegBytes = byteArrayOf(-1, -40, -1, -39)
        val bitmap = mock(android.graphics.Bitmap::class.java)
        `when`(bitmap.width).thenReturn(1)
        `when`(bitmap.height).thenReturn(1)
        doAnswer { invocation -> (invocation.getArgument<java.io.OutputStream>(2)).write(jpegBytes); true }.`when`(bitmap).compress(any(), anyInt(), any())
        val decoder = mockStatic(android.graphics.BitmapFactory::class.java)
        decoder.`when`<android.graphics.Bitmap> { android.graphics.BitmapFactory.decodeByteArray(any(), anyInt(), anyInt()) }.thenReturn(bitmap)
        var frameReads = 0
        val frameEntered = CompletableDeferred<Unit>()
        val frameRelease = CompletableDeferred<Unit>()
        set(assistant, "liveFrame", suspend {
            frameReads++
            if (endDuringFrame || revokeDuringFrame || replaceDuringFrame) { frameEntered.complete(Unit); frameRelease.await() }
            if (jpeg) jpegBytes else null
        })
        set(assistant, "liveAiDetailed", { false })
        set(assistant, "liveAiVoiceOnly", { setting })
        set(assistant, "glassesVoice", { "voice-id" })
        set(assistant, "_phase", MutableStateFlow(AssistantPhase.WAITING))
        val notes = MutableSharedFlow<String>(extraBufferCapacity = 64)
        set(assistant, "_notes", notes)
        val diagnostics = mutableListOf<String>()
        val noteJob = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            notes.collect { if (it.startsWith("NativeDelivery:")) diagnostics.add(it) }
        }
        val writes = java.util.concurrent.CopyOnWriteArrayList<Sent>()
        val audioTypes = java.util.concurrent.CopyOnWriteArrayList<Int?>()
        var stopLive: (() -> Unit)? = null
        val mux = mock(DataXConnection::class.java) { invocation ->
            if (invocation.method.name == "send") {
                if (invocation.getArgument<Int>(0) == 8) {
                    audioTypes.add(invocation.getArgument(2))
                    return@mock Unit
                }
                val event = parseNetEvent(invocation.getArgument(1))
                writes.add(Sent(invocation.getArgument(2), event))
                if (event.id == stopAfter) stopLive?.invoke()
                Unit
            } else RETURNS_DEFAULTS.answer(invocation)
        }
        val linkClass = Class.forName("com.macedonga.visor.core.assistant.Assistant\$Link")
        val link = linkClass.getDeclaredConstructor(DataXConnection::class.java).apply { isAccessible = true }.newInstance(mux)
        set(link, "m0", 7)
        set(link, "s0", 8)
        set(link, "simplified", simplified)
        set(assistant, "link", link)
        val hostClass = Class.forName("com.macedonga.visor.core.assistant.Assistant\$liveAi\$1")
        val host = hostClass.getDeclaredConstructor(Assistant::class.java).apply { isAccessible = true }.newInstance(assistant) as LiveAiHost
        val job = SupervisorJob()
        val live = LiveAi(context, CoroutineScope(job + Dispatchers.Unconfined), host, { 0L })
        set(assistant, "liveAi", live)
        val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val errors = MutableSharedFlow<ProtocolError>(extraBufferCapacity = 64)
        val connection = mock(GlassesConnection::class.java, CALLS_REAL_METHODS)
        doReturn(null).`when`(connection).pause(anyString())
        set(connection, "_protocolErrors", errors)
        set(connection, "protocolErrors", errors)
        set(connection, "_warnings", MutableSharedFlow<String>(extraBufferCapacity = 64))
        set(assistant, "connection", connection)
        set(assistant, "scope", eventScope)
        set(assistant, "progressTurn", java.util.concurrent.atomic.AtomicLong())
        assistant.watchProtocolErrors(connection.protocolErrors)
        val camera = mock(LiveAiCamera::class.java)
        set(assistant, "camera", camera)
        val wifiHostEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
        val hostMux = mux
        val staleMux = mock(DataXConnection::class.java)
        val wifiHost = mock(SessionHost::class.java) { invocation ->
            when (invocation.method.name) {
                "getEvents" -> wifiHostEvents
                "submit" -> {
                    val work = invocation.getArgument<suspend (DataXConnection) -> Unit>(0)
                    eventScope.launch { work(hostMux) }; null
                }
                else -> RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val stream = unsafe.allocateInstance(LiveStream::class.java) as LiveStream
        set(stream, "scope", eventScope)
        set(stream, "connection", connection)
        set(stream, "hosts", SessionHostFactory { _, _ -> wifiHost })
        set(stream, "_log", MutableStateFlow<List<String>>(emptyList()))
        set(stream, "_notes", MutableSharedFlow<String>(extraBufferCapacity = 64))
        set(stream, "_wifiSession", MutableStateFlow<DataXConnection?>(null))
        val opening = eventScope.async { call(stream, "openWifiSession", GlassesTarget(GlassesLink.Wifi("127.0.0.1", 1234), "test"), mock(com.macedonga.visor.core.identity.IdentityBundle::class.java), "127.0.0.1", 1234, null) }
        wifiHostEvents.emit(LinkStateChanged(LinkState.READY))
        opening.await()
        suspend fun closeWifi(stale: Boolean = false) {
            val token = live.turnGeneration
            wifiHostEvents.emit(ProtocolError("service 15 answered status 0xc002 on channel 0x0008", origin = if (stale) staleMux else hostMux))
            yield()
            assertEquals("Only owned closure revokes generation", !stale, live.turnGeneration != token)
        }
        stopLive = { live.end("native write stop") }
        try {
            var sub = "ordinary-private-request"
            if (liveTurn) {
                live.onModeLiveAi("session", emptyMap())
                if (replaceDuringFrame || replaceDuringModel) {
                    live.onStartInteraction(mapOf(1 to "prior", 5 to 3), java.util.Locale.US)
                    writes.clear()
                }
                live.javaClass.getDeclaredMethod("heard", String::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(live, "Question", 1234L, 5678L)
                if (revokeDuringModel || replaceDuringModel || disableDuringModel || closeDuringModel) {
                    withTimeout(3000) { modelEntered.await() }
                    if (closeDuringModel) {
                        closeWifi()
                        assertFalse("Owned Wi-Fi error must revoke live generation", live.active)
                        verify(camera).off()
                    }
                    else if (replaceDuringModel) live.onStartInteraction(mapOf(1 to "replacement", 5 to 3), java.util.Locale.US)
                    else if (disableDuringModel) (store.config as MutableStateFlow).value = store.config.value.copy(enabled = false)
                    else { setting = true; live.refreshCameraSetting() }
                    modelRelease.complete(Unit)
                    delay(30)
                    assertFalse("Suspended model preparation must not dispatch image", dispatched)
                    if (closeDuringModel) assertTrue("No speech after revoked model", audioTypes.isEmpty())
                    assertEquals(listOf(EV_START_OF_USER_SPEECH, EV_FINAL_TRANSCRIPTION), writes.map { it.event.id }.filterNot { it in listOf(EV_MWA_INTERACTION_CREATION_SUCCESS, EV_SERVER_CONNECTION_STATUS, EV_KEEP_AIR_SESSION_ALIVE) })
                    return
                }
                if (endDuringFrame || revokeDuringFrame || replaceDuringFrame) {
                    withTimeout(3000) { frameEntered.await() }
                    if (replaceDuringFrame) live.onStartInteraction(mapOf(1 to "replacement", 5 to 3), java.util.Locale.US)
                    else if (endDuringFrame) live.end("frame suspended") else { setting = true; live.refreshCameraSetting() }
                    frameRelease.complete(Unit)
                    delay(30)
                    assertTrue("Ended session must not dispatch model", mockingDetails(brain).invocations.none { it.method.name == "answer" })
                    assertEquals(listOf(EV_START_OF_USER_SPEECH, EV_FINAL_TRANSCRIPTION), writes.map { it.event.id }.filterNot { it in listOf(EV_MWA_INTERACTION_CREATION_SUCCESS, EV_SERVER_CONNECTION_STATUS, EV_KEEP_AIR_SESSION_ALIVE) })
                    return
                }
                if (stopDuringSynthesis || closeDuringSynthesis || staleCloseDuringSynthesis) {
                    withTimeout(3000) { synthesisEntered.await() }
                    if (closeDuringSynthesis || staleCloseDuringSynthesis) {
                        closeWifi(staleCloseDuringSynthesis)
                        if (staleCloseDuringSynthesis) {
                            assertTrue(live.active)
                            synthesisRelease.complete(Unit)
                            withTimeout(3000) { while (audioTypes.none { it == MT_STREAM_END }) delay(5) }
                            return
                        }
                    } else live.end("synthesis suspended")
                    val stoppedWrites = writes.size
                    synthesisRelease.complete(Unit)
                    delay(100)
                    assertEquals(stoppedWrites, writes.size)
                    assertTrue(audioTypes.isEmpty())
                    assertFalse(live.active)
                    if (closeDuringSynthesis) verify(camera).off()
                    assertTrue(store.config.value.glassesVoice)
                    return
                }
                if (stopAfter != null) {
                    withTimeout(3000) { while (writes.none { it.event.id == stopAfter }) delay(5) }
                    val stoppedWrites = writes.size
                    delay(100)
                    assertEquals("No writes after Stop", stoppedWrites, writes.size)
                    assertFalse(live.active)
                    return
                }
                withTimeout(3000) { while (writes.none { it.event.id == EV_KEEP_AIR_SESSION_ALIVE }) delay(5) }
                sub = Thrift.loads(writes.first().event.body)[1].asText()
                java.util.UUID.fromString(sub)
            } else {
                call(assistant, "deliver", link, sub, "Question", "Native reply.")
            }
            assertTrue("Saved ordinary native preference unchanged", store.config.value.glassesVoice)
            assertTrue("No saved settings writes", mockingDetails(store).invocations.all { it.method.name.startsWith("get") })
            if (liveTurn) assertEquals("Unread setting must not read even cached Live frames", if (voiceOnly == false) 1 else 0, frameReads)
            if (jpeg && voiceOnly == false) assertArrayEquals(jpegBytes, modelImage) else assertNull(modelImage)
            if (expectPhone) {
                assertEquals(listOf(MT_STREAM_HEADER, MT_STREAM_AUDIO, null, MT_STREAM_END), audioTypes)
                assertArrayEquals(varm(sub, "Question", "Native reply."), writes.single { it.event.id == EV_VOICE_ACTION_RESPONSE }.event.body)
                assertTrue(writes.any { it.event.id == EV_TTS_START_INFO })
                assertTrue(writes.any { it.event.id == EV_TTS_END_INFO })
                assertFalse(writes.any { it.event.id == EV_MWA_VOICE_INTERACTION_COMPLETE })
                return
            }
            val nativeOrder = if (simplified && !liveTurn) listOf(EV_VOICE_ACTION_RESPONSE, EV_MWA_VOICE_INTERACTION_COMPLETE)
                else listOf(EV_MWA_VOICE_INTERACTION_COMPLETE, EV_VOICE_ACTION_RESPONSE)
            assertEquals("one speech/transcription pair only", listOf(EV_START_OF_USER_SPEECH, EV_FINAL_TRANSCRIPTION, EV_ON_PHONE_UNDERSTANDING) + nativeOrder + EV_KEEP_AIR_SESSION_ALIVE, writes.map { it.event.id })
            val expected71 = ThriftWriter().str(1, sub).apply { if (liveTurn) i64(2, 1234L) }.done()
            val expected52 = ThriftWriter().str(1, "Question").i32(2, 0).str(4, "").bool(7, false).str(8, sub).apply { if (liveTurn) i64(13, 5678L) }.done()
            assertArrayEquals(expected71, writes[0].event.body)
            assertArrayEquals(expected52, writes[1].event.body)
            assertEquals(if (liveTurn) MT_NET_EVENT else 0, writes[1].type)
            assertEquals(0, writes[2].type)
            assertArrayEquals(ThriftWriter().str(1, sub).bool(2, false).str(3, REPLY_INTENT).bool(4, false).done(), writes[2].event.body)
            assertArrayEquals(varm(sub, "Question", "Native reply.", onGlasses = true, voiceId = "voice-id"), writes.single { it.event.id == EV_VOICE_ACTION_RESPONSE }.event.body)
            assertArrayEquals(interactionComplete(sub, "Native reply.", expectsAnswer = false), writes.single { it.event.id == EV_MWA_VOICE_INTERACTION_COMPLETE }.event.body)
            assertTrue(writes.drop(3).all { it.type == MT_NET_EVENT })
            yield()
            assertTrue("native transport diagnostics missing", diagnostics.any { "stage=begin" in it })
            assertTrue(diagnostics.any { "mode=${if (liveTurn) "live" else "ordinary"}" in it })
            assertTrue(diagnostics.any { "simplified=$simplified" in it })
            assertEquals("Ordering must not mutate effective forwarding state", simplified, linkClass.getDeclaredField("simplified").apply { isAccessible = true }.get(link))
            assertTrue(diagnostics.any { "stage=sent event=53" in it && "streaming=false partial=false" in it })
            assertTrue(diagnostics.any { "stage=sent event=60" in it && "status=1000" in it })
            assertTrue(diagnostics.any { "stage=complete" in it })
            assertTrue(diagnostics.none { "Question" in it || "Native reply." in it || "voice-id" in it || sub in it })
        } finally { eventScope.cancel(); noteJob.cancel(); job.cancel(); decoder.close(); directory.deleteRecursively() }
    }
    @Test fun wifiChannelCloseDuringBlockedModelPreventsImageRequest() = runBlocking { sequence(true, true, false, jpeg = true, closeDuringModel = true) }
    @Test fun disableDuringBlockedModelPreventsImageRequest() = runBlocking { sequence(true, true, false, jpeg = true, disableDuringModel = true) }
    @Test fun ownedChannelCloseDuringSynthesisRevokesSpeech() = runBlocking { sequence(true, true, closeDuringSynthesis = true) }
    @Test fun staleChannelCloseCannotRevokeReplacement() = runBlocking { sequence(true, true, staleCloseDuringSynthesis = true) }
    @Test fun stopDuringPhoneSynthesisRevokesAllSpeechWrites() = runBlocking { sequence(true, true, stopDuringSynthesis = true) }
    @Test fun liveUsesPhoneStreamWithSavedNativePreference() = runBlocking { sequence(true, true, expectPhone = true) }
    @Test fun stopAfterTtsStartPreventsAudioForBothForwardingModes() = runBlocking { for (simplified in listOf(true, false)) sequence(simplified, true, stopAfter = EV_TTS_START_INFO) }
    @Test fun stopAfterVarmPreventsKeepaliveForBothForwardingModes() = runBlocking { for (simplified in listOf(true, false)) sequence(simplified, true, stopAfter = EV_VOICE_ACTION_RESPONSE) }
    @Test fun replacementDuringFramePreventsOldDispatchAndDelivery() = runBlocking { mockConstruction(GlassesEars::class.java).use { sequence(true, true, false, jpeg = true, replaceDuringFrame = true) } }
    @Test fun replacementDuringModelPreventsOldDispatchAndDelivery() = runBlocking { mockConstruction(GlassesEars::class.java).use { sequence(true, true, false, jpeg = true, replaceDuringModel = true) } }
    @Test fun revocationDuringModelPreparationPreventsImageDispatch() = runBlocking { sequence(true, true, false, jpeg = true, revokeDuringModel = true) }
    @Test fun nonNullJpegAttachedOnlyWhenExplicitlyOff() = runBlocking { sequence(true, true, false, jpeg = true) }
    @Test fun nonNullCachedJpegBlockedWhenOn() = runBlocking { sequence(true, true, true, jpeg = true) }
    @Test fun nonNullCachedJpegBlockedWhenUnknown() = runBlocking { sequence(true, true, null, jpeg = true) }
    @Test fun revocationDuringFramePreventsDispatch() = runBlocking { sequence(true, true, false, jpeg = true, revokeDuringFrame = true) }
    @Test fun sessionEndDuringFramePreventsDispatch() = runBlocking { sequence(true, true, false, endDuringFrame = true) }
    @Test fun voiceOnlyOnSkipsCachedFrame() = runBlocking { sequence(true, true, true) }
    @Test fun voiceOnlyOffReadsLiveFrame() = runBlocking { sequence(true, true, false) }
    @Test fun livePhoneSimplifiedKeepsOriginalAnnouncement() = runBlocking { sequence(true, true) }
    @Test fun livePhoneFullKeepsOriginalAnnouncement() = runBlocking { sequence(false, true) }
    @Test fun ordinaryNativeSimplifiedUnchanged() = runBlocking { sequence(true, false) }
    @Test fun ordinaryNativeFullUnchanged() = runBlocking { sequence(false, false) }
}
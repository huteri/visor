package com.macedonga.visor.core.assistant

import android.content.Context
import com.macedonga.visor.core.protocol.ThriftWriter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What Live AI needs from the assistant around it. */
internal interface LiveAiHost {
    /** Host APK version opt-in, never inferred from library build type. */
    val rawAudioCaptureEnabled: Boolean get() = false
    /** Null means unread: fail closed, never infer voice-only OFF. */
    val voiceOnly: Boolean? get() = null
    /** A NetEvent on the message channel. */
    suspend fun event(id: Int, body: ByteArray)

    /** One turn: the model sees the camera and hears [question]; the answer is spoken as [subInteraction]. */
    suspend fun answer(subInteraction: String, question: String)

    /** Existing SessionEnd path, supplied by assistant transport. */
    suspend fun stopSession(session: String) = Unit
    /** The camera stream the session sees through, on 51350 as the glasses' MMAI_LIVE app. */
    fun cameraOn()
    fun cameraOff()
    fun note(text: String)
}

/** Where a Live AI session has got to. */
enum class LiveAiPhase { OFF, STARTING, LISTENING, THINKING }

/**
 * Meta's Live AI with Visor as both the phone app and the server. The glasses switch their session to LIVE_AI
 * (ev289, mode 2), open an interaction for it (START_INTERACTION), start their MMAI_LIVE camera app and stream
 * the microphone on s0 with their own speech recognition unloaded. The server acknowledges the interaction,
 * listens, and answers each utterance as a sub-interaction of it, with the camera's picture.
 */
internal class LiveAi(private val context: Context, private val scope: CoroutineScope, private val host: LiveAiHost,
    private val elapsedMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private var rawCapture: RawMicrophoneCapture? = null
    private var cameraRequested = false
    private val turns = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private val generation = AtomicInteger()
    private val sessionGeneration = AtomicInteger()

    internal fun turnIsCurrent(token: Int): Boolean = active && generation.get() == token
    internal val turnGeneration: Int get() = generation.get()

    private fun revokeTurns() {
        generation.incrementAndGet()
        turns.forEach { it.cancel() }
        turns.clear()
    }

    private fun ensureCamera() {
        if (host.voiceOnly != false) {
            if (cameraRequested) { revokeTurns(); host.cameraOff() }
            cameraRequested = false
            host.note("Live AI camera: requested=false voiceOnly=${host.voiceOnly}")
            return
        }
        if (cameraRequested) return
        cameraRequested = true
        host.cameraOn()
        host.note("Live AI camera: requested=true")
    }

    private fun beginRawCapture() {
        rawCapture?.close()
        rawCapture = RawMicrophoneCapture(host.rawAudioCaptureEnabled, elapsedMs, host::note)
    }
    private val _phase = MutableStateFlow(LiveAiPhase.OFF)
    val phase: StateFlow<LiveAiPhase> = _phase.asStateFlow()

    /** The session id from ev289, and the Live AI interaction from START_INTERACTION. */
    @Volatile var sessionId = ""
        private set
    @Volatile var interaction = ""
        private set

    private var ears: GlassesEars? = null
    private var keepalive: Job? = null
    private var heardAudio = false
    private val nonce = AtomicInteger(1)
    private var micChunks = 0
    private var inputStats = MicrophoneInputStats()
    private var inputDiagnostics: Job? = null

    private fun inputSnapshot() = host.note("Live AI stages: input-summary ${inputStats.snapshot()} ${ears?.diagnosticSnapshot() ?: "ears=absent"}")

    val active: Boolean get() = _phase.value != LiveAiPhase.OFF
    internal val imageAllowed: Boolean get() = active && host.voiceOnly == false

    fun refreshCameraSetting() {
        if (active) ensureCamera()
    }

    /** ev289 with mode LIVE_AI: the glasses are switching; the camera stream is the phone's to open. */
    fun onModeLiveAi(session: String, fields: Map<Int, Any?>) {
        host.note("Live AI: session mode LIVE_AI for $session (${deep(fields)})")
        if (active && session == sessionId) return
        sessionGeneration.incrementAndGet()
        if (active) revokeTurns()
        if (!active) beginRawCapture()
        sessionId = session
        _phase.value = LiveAiPhase.STARTING
        ensureCamera()
    }

    /** START_INTERACTION for Live AI: acknowledge it the way the server does, then listen. */
    suspend fun onStartInteraction(fields: Map<Int, Any?>, locale: Locale) {
        val id = fields[1].asText()
        host.note("Live AI: start interaction $id, use case ${fields[5]}, method ${fields[3]} (${deep(fields)})")
        if (id.isEmpty()) return
        if (active && interaction == id) {
            ensureCamera()
            return
        }
        val startup = sessionGeneration.incrementAndGet()
        if (active) revokeTurns() else beginRawCapture()
        ensureCamera()
        interaction = id
        heardAudio = false
        micChunks = 0
        inputStats = MicrophoneInputStats()
        inputDiagnostics?.cancel()
        _phase.value = LiveAiPhase.STARTING
        val startupContext = kotlin.coroutines.coroutineContext
        fun startupCurrent(): Boolean {
            startupContext.ensureActive()
            return active && sessionGeneration.get() == startup && interaction == id
        }
        // As the server answers a new interaction: created, connected (twice), then the stream's first ack.
        val creation = ThriftWriter().str(1, id).bool(2, true).bool(3, false)
            .str(4, UUID.randomUUID().toString().uppercase()).str(5, SERVER_HOST)
            .str(6, "ble").str(10, "${System.currentTimeMillis()}-${UUID.randomUUID()}").bool(11, false).bool(13, true).bool(14, false).done()
        host.event(EV_MWA_INTERACTION_CREATION_SUCCESS, creation)
        if (!startupCurrent()) return
        delay(20)
        if (!startupCurrent()) return
        host.event(EV_SERVER_CONNECTION_STATUS, ThriftWriter().str(1, id).bool(2, false).bool(3, false).i32(4, 1).bool(7, false).done())
        if (!startupCurrent()) return
        delay(20)
        if (!startupCurrent()) return
        host.event(EV_SERVER_CONNECTION_STATUS, ThriftWriter().str(1, id).bool(2, true).bool(3, false).i32(4, 2).done())
        if (!startupCurrent()) return
        delay(20)
        if (!startupCurrent()) return
        host.event(EV_KEEP_AIR_SESSION_ALIVE, ThriftWriter().str(1, "AsrStartListening").i32(2, 20_000).i32(3, nonce.getAndIncrement()).done())
        if (!startupCurrent()) return
        ears?.stop()
        ears = GlassesEars(context, locale, continuous = true, onUtterance = ::heard, onProblem = { host.note("Live AI: $it") }, onDiagnostic = { host.note("Live AI stages: $it") }).also { it.start() }
        if (!startupCurrent()) return
        keepalive?.cancel()
        keepalive = scope.launch { keepAlive() }
        // Three bounded snapshots survive a log export before the session ends. No payload bytes.
        inputDiagnostics = scope.launch {
            repeat(3) {
                delay(5_000)
                if (active) inputSnapshot()
            }
        }
    }

    /** The glasses' own header for their microphone stream. */
    fun onMicHeader(chunk: ByteArray, messageType: Int, channelId: Int, hasExtension: Boolean) {
        if (!active) return
        rawCapture?.header(chunk, messageType, channelId, hasExtension)
        host.note("Live AI stages: microphone-header cameraModeSeen=${sessionId.isNotEmpty()}")
    }

    fun onMicShutdown(chunk: ByteArray, messageType: Int, channelId: Int, hasExtension: Boolean) {
        if (active) rawCapture?.shutdown(chunk, messageType, channelId, hasExtension)
    }

    /** A chunk of the microphone. The first one is the audio payload the server acknowledges as received. */
    fun onMic(chunk: ByteArray, messageType: Int, channelId: Int, hasExtension: Boolean) {
        if (!active) return
        rawCapture?.packet(chunk, messageType, channelId, hasExtension)
        inputStats.record(chunk.size, messageType, channelId, hasExtension)
        if (micChunks++ == 0) host.note("Live AI stages: first-input bytes=${chunk.size} type=$messageType channel=$channelId extensions=$hasExtension")
        if (!heardAudio && interaction.isNotEmpty()) {
            heardAudio = true
            scope.launch { firstPayload(PAYLOAD_AUDIO) }
        }
        ears?.hear(chunk)
    }

    /** The camera's first frame, acknowledged as the video payload. */
    fun onFirstFrame() {
        if (imageAllowed && interaction.isNotEmpty()) {
            host.note("Live AI camera: frameAvailable=true")
            scope.launch { firstPayload(PAYLOAD_VIDEO) }
        }
    }

    private suspend fun firstPayload(type: Int) {
        val token = turnGeneration
        val id = interaction
        if (!turnIsCurrent(token)) return
        host.event(EV_LIVE_AI_CLIENT_RECEIVED_FIRST_FRAME, ThriftWriter().str(1, id).i32(2, type).i32(3, 0).done())
        delay(20)
        if (!turnIsCurrent(token)) return
        val now = System.currentTimeMillis()
        host.event(
            EV_LIVE_AI_STREAM_INITIATION_ACK,
            ThriftWriter().i32(1, type).i64(2, now).i64(3, now).str(4, id).str(5, "$SERVER_HOST:tsp_lla/assistant/sgserver").done(),
        )
        host.note("Live AI: acknowledged the first ${if (type == PAYLOAD_AUDIO) "audio" else "video"}")
        if (type == PAYLOAD_AUDIO && turnIsCurrent(token)) _phase.value = LiveAiPhase.LISTENING
    }

    /** An utterance the ears finished: a sub-interaction opens with its speech, then the answer follows. */
    private fun heard(text: String, startMs: Long, endMs: Long) {
        if (!active) return
        // Device control, not a question for the model. Revoke before any suspended transport.
        if (text.trim().matches(Regex("stop\\s+live\\s+ai[.!?]*", RegexOption.IGNORE_CASE))) {
            val session = sessionId.ifEmpty { interaction }
            end("local stop command")
            scope.launch { host.stopSession(session) }
            return
        }
        val sub = UUID.randomUUID().toString()
        host.note("Live AI: heard \"$text\" ($startMs to $endMs ms)")
        val token = turnGeneration
        val turn = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            if (!turnIsCurrent(token)) return@launch
            host.event(EV_START_OF_USER_SPEECH, ThriftWriter().str(1, sub).i64(2, startMs).done())
            host.event(EV_FINAL_TRANSCRIPTION, ThriftWriter().str(1, text).i32(2, 0).str(4, "").bool(7, false).str(8, sub).i64(13, endMs).done())
            _phase.value = LiveAiPhase.THINKING
            try {
                host.answer(sub, text)
            } finally {
                if (turnIsCurrent(token)) _phase.value = LiveAiPhase.LISTENING
            }
        }
        turns.add(turn)
        turn.invokeOnCompletion { turns.remove(turn) }
        turn.start()
    }

    /** FrameKeepAlive and KeepWearableAirSessionAlive, as the app keeps a session it is serving. */
    private suspend fun keepAlive() {
        while (scope.isActive && active) {
            val token = turnGeneration
            delay(KEEPALIVE_MS)
            if (!turnIsCurrent(token)) continue
            host.event(EV_FRAME_KEEP_ALIVE, ThriftWriter().i64(1, System.currentTimeMillis()).i32(2, nonce.getAndIncrement()).done())
            host.event(EV_KEEP_AIR_SESSION_ALIVE, ThriftWriter().str(1, "LiveAiSession").i32(2, 20_000).i32(3, nonce.getAndIncrement()).done())
        }
    }

    /** The glasses ended it, or the link went: stop listening and close the camera. */
    fun end(why: String) {
        if (!active) return
        sessionGeneration.incrementAndGet()
        revokeTurns()
        inputDiagnostics?.cancel()
        inputDiagnostics = null
        rawCapture?.close()
        rawCapture = null
        inputSnapshot()
        host.note("Live AI: ended ($why)")
        _phase.value = LiveAiPhase.OFF
        keepalive?.cancel()
        keepalive = null
        ears?.stop()
        ears = null
        interaction = ""
        sessionId = ""
        cameraRequested = false
        host.cameraOff()
    }

    private companion object {
        const val SERVER_HOST = "prod.facebookvirtualassistant.com"
        const val PAYLOAD_AUDIO = 1
        const val PAYLOAD_VIDEO = 2
        const val KEEPALIVE_MS = 5_000L
    }
}

package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.protocol.ThriftWriter
import kotlinx.coroutines.*

/** Experimental: repeated native VARMs in one interaction are not yet validated on hardware. */
internal class NativeProgressWire(
    private val lease: DiagnosticLease,
    private val interaction: String,
    private val transcript: String,
    private val voiceId: String?,
    private val pause: suspend (Long) -> Unit,
    private val send: suspend (ByteArray, Int) -> Unit,
) {
    private var opened = false
    private var finished = false
    private var sequence = 1
    private suspend fun event(id: Int, body: ByteArray, type: Int = MT_NET_EVENT) = lease.send { send(netEvent(id, body), type) }
    suspend fun speak(text: String, final: Boolean) {
        check(!finished)
        if (!opened) {
            event(EV_START_OF_USER_SPEECH, ThriftWriter().str(1, interaction).done())
            pause(20)
            event(EV_FINAL_TRANSCRIPTION, ThriftWriter().str(1, transcript).i32(2, 0).str(4, "").bool(7, false).str(8, interaction).done(), 0)
            pause(20)
            event(EV_ON_PHONE_UNDERSTANDING, ThriftWriter().str(1, interaction).bool(2, false).str(3, REPLY_INTENT).bool(4, false).done(), 0)
            pause(20)
            opened = true
        }
        event(EV_VOICE_ACTION_RESPONSE, varm(interaction, transcript, text, onGlasses = true, voiceId = voiceId))
        if (final) {
            pause(20)
            event(EV_MWA_VOICE_INTERACTION_COMPLETE, interactionComplete(interaction, text))
            pause(20)
            val mono = System.nanoTime() / 1_000_000
            event(EV_KEEP_AIR_SESSION_ALIVE, ThriftWriter().str(1, "VoiceActionResponseMessage").i32(2, (mono and 0xFFFF).toInt()).i32(3, 1).done())
            finished = true
        }
    }
    suspend fun keepalive() {
        check(!finished)
        event(EV_KEEP_AIR_SESSION_ALIVE, ThriftWriter().str(1, "AwaitingResponse").i32(2, 20_000).i32(3, sequence++).done())
    }
}

internal class NativeProgress(private val now: () -> Long, private val pause: suspend (Long) -> Unit) {
    suspend fun run(
        lease: DiagnosticLease, wire: NativeProgressWire, acknowledgement: String,
        update: suspend () -> String?, model: suspend () -> Reply,
    ): ProgressResult = withTimeout(ProgressSpeech.BUDGET_MS) {
        coroutineScope {
            val started = now()
            val answer = async { runCatching { lease.prepare { model() } } }
            var phrase: Deferred<String?>? = null
            var nextUpdate = started + 15_000
            var nextKeepalive = started + 3_000
            try {
                if (!answer.isCompleted) wire.speak(acknowledgement, false)
                while (true) {
                    lease.check()
                    if (answer.isCompleted) {
                        phrase?.cancel()
                        val reply = answer.await().getOrThrow()
                        val modelMs = now() - started
                        wire.speak(reply.text, true)
                        return@coroutineScope ProgressResult(reply, modelMs, 0)
                    }
                    if (phrase?.isCompleted == true) {
                        val text = phrase.await()
                        phrase = null
                        lease.check()
                        if (!answer.isCompleted && text != null) wire.speak(text, false)
                    }
                    if (now() >= nextKeepalive) {
                        wire.keepalive()
                        nextKeepalive = now() + 3_000
                    }
                    if (now() >= nextUpdate) {
                        nextUpdate = now() + 15_000
                        if (phrase == null) phrase = async { update() }
                    }
                    pause(20)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            } finally {
                phrase?.cancel()
                answer.cancel()
            }
        }
    }
}

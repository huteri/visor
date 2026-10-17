package com.macedonga.visor.core.assistant

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Ordinary answer framing; diagnostic callers retain their deterministic first-audio contract. */
internal class AnswerWire(
    private val simplified: Boolean,
    private val pause: suspend (Long) -> Unit,
    private val send: suspend (Int, ByteArray, Int?) -> Unit,
) {
    suspend fun native(m0: Int, interaction: String, transcript: String, text: String, voiceId: String?) {
        val answer = netEvent(EV_VOICE_ACTION_RESPONSE, varm(interaction, transcript, text, onGlasses = true, voiceId = voiceId))
        val complete = netEvent(EV_MWA_VOICE_INTERACTION_COMPLETE, interactionComplete(interaction, text))
        // Without simplified forwarding the VARM ends the turn as soon as it lands.
        for (event in if (simplified) listOf(answer, complete) else listOf(complete, answer)) {
            send(m0, event, MT_NET_EVENT)
            pause(20)
        }
    }

    suspend fun phone(m0: Int, s0: Int, answer: ByteArray, packets: List<OpusPacket>, now: () -> Long) {
        val start = now()
        var dueUs = 0L
        for ((i, chunk) in packets.chunked(2).withIndex()) {
            val frame = chunk.fold(ByteArray(0)) { acc, p ->
                acc + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(p.data.size).array() + p.data
            }
            if (i == 0) {
                send(s0, frame, MT_STREAM_AUDIO)
                if (simplified) send(m0, answer, MT_NET_EVENT)
            } else {
                // Continuations must be bare, not explicitly tagged type 0.
                send(s0, frame, null)
            }
            dueUs += chunk.sumOf { it.durationUs }
            val wait = dueUs / 1000 - (now() - start)
            if (wait > 0) pause(wait)
        }
        send(s0, ByteArray(0), MT_STREAM_END)
        if (!simplified) send(m0, answer, MT_NET_EVENT)
    }
}

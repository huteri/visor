package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.protocol.ThriftWriter
import com.macedonga.visor.core.protocol.Wire
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Interaction boundary shared by Android routing and deterministic integration tests. */
internal class ProgressTurn(private val now: () -> Long) {
    suspend fun run(
        lifetime: DiagnosticLifetime, ticket: Long, aliases: Set<String>, streamId: String,
        controller: (DiagnosticLease, String) -> ProgressSpeech,
        acknowledgement: String, progress: String, model: suspend () -> Reply,
        completed: (ProgressResult) -> Unit,
        failed: (Throwable) -> Unit = {},
        ownership: () -> Boolean = { true },
    ) {
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val lease = lifetime.begin(ticket, aliases, now, onCancel = { job?.cancel() }, budgetMs = ProgressSpeech.BUDGET_MS, allowed = ownership) ?: return
        try {
            lease.check()
            require(streamId.isNotBlank()) { "no interaction-owned progress stream" }
            val result = controller(lease, streamId).run(lease, acknowledgement, progress, model)
            lease.prepare { lifetime.publish(ticket) { completed(result) } }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lease.prepare { lifetime.publish(ticket) { failed(error) } }
            throw error
        } finally { lease.revoke() }
    }
}

internal class ProgressWire(
    private val lease: DiagnosticLease,
    private val interaction: String, private val transcript: String, private val acknowledgement: String,
    private val streamId: String, private val m0: Int, private val s0: Int,
    private val now: () -> Long, private val wall: () -> Long,
    private val pause: suspend (Long) -> Unit,
    private val send: suspend (Int, ByteArray, Int?) -> Unit,
) : ProgressSink {
    private var first = true
    private var mono = 0L
    private var seq = 1
    private suspend fun write(channel: Int, bytes: ByteArray, type: Int? = null) = lease.send { send(channel, bytes, type) }
    private suspend fun event(id: Int, body: ByteArray, type: Int = MT_NET_EVENT) = write(m0, netEvent(id, body), type)
    override suspend fun start() {
        require(streamId.isNotBlank()) { "no interaction-owned progress stream" }
        event(EV_START_OF_USER_SPEECH, ThriftWriter().str(1, interaction).done())
        pause(20)
        event(EV_FINAL_TRANSCRIPTION, ThriftWriter().str(1, transcript).i32(2, 0).str(4, "").bool(7, false).str(8, interaction).done(), 0)
        pause(20)
        event(EV_ON_PHONE_UNDERSTANDING, ThriftWriter().str(1, interaction).bool(2, false).str(3, REPLY_INTENT).bool(4, false).done(), 0)
        pause(20)
        mono = now()
        event(EV_TTS_START_INFO, ThriftWriter().i64(1, wall()).i64(2, mono).str(3, interaction).done())
        pause(20)
        val header = Wire.fieldBytes(1, streamId.toByteArray()) + Wire.fieldBytes(2, interaction.toByteArray()) +
            Wire.fieldVarint(4, 1) + Wire.fieldVarint(7, 1) + Wire.fieldVarint(19, 1)
        write(s0, header, MT_STREAM_HEADER)
    }
    override suspend fun audio(packet: OpusPacket) {
        val frame = ByteBuffer.allocate(4 + packet.data.size).order(ByteOrder.LITTLE_ENDIAN).putInt(packet.data.size).put(packet.data).array()
        write(s0, frame, if (first) MT_STREAM_AUDIO else null)
        if (first) {
            // Metadata opens the live stream; it is not a second model/history answer.
            event(EV_VOICE_ACTION_RESPONSE, varm(interaction, transcript, acknowledgement))
            first = false
        }
    }
    override suspend fun end() {
        check(!first) { "no progress audio" }
        write(s0, ByteArray(0), MT_STREAM_END)
        event(EV_TTS_END_INFO, ThriftWriter().i64(1, wall()).i64(2, mono).i64(3, 7000).str(4, interaction).done())
        pause(20)
        event(EV_KEEP_AIR_SESSION_ALIVE, ThriftWriter().str(1, "VoiceActionResponseMessage").i32(2, (mono and 0xFFFF).toInt()).i32(3, 1).done())
    }
    override suspend fun keepalive() {
        event(EV_KEEP_AIR_SESSION_ALIVE, ThriftWriter().str(1, "AwaitingResponse").i32(2, 20_000).i32(3, seq++).done())
    }
}

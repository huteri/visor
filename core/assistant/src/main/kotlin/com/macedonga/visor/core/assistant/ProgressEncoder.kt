package com.macedonga.visor.core.assistant

import kotlinx.coroutines.ensureActive

internal data class CodecOutput(val packet: OpusPacket?, val end: Boolean)
internal interface ProgressCodec : AutoCloseable {
    fun offer(pcm: ShortArray, ptsUs: Long, end: Boolean): Boolean
    fun poll(): CodecOutput?
}
internal class ProgressEncoderPump(
    private val codec: ProgressCodec,
    private val now: () -> Long,
    private val pause: suspend (Long) -> Unit,
) : ProgressEncoder {
    private var samples = 0L
    private var emittedUs = 0L
    private var ended = false
    private var closed = false
    override suspend fun encode(pcm: ShortArray, end: Boolean): List<OpusPacket> {
        check(!ended && !closed) { "encoder is closed" }
        require((end && pcm.isEmpty()) || (!end && pcm.size in 1..960)) { "invalid incremental PCM block" }
        val packets = ArrayList<OpusPacket>()
        val deadline = now() + 2_000
        suspend fun checkActive() {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            check(now() < deadline) { "Opus encoder stalled" }
        }
        fun drain(): Boolean {
            var eos = false
            repeat(128) {
                val output = codec.poll() ?: return eos
                output.packet?.let { packet ->
                    require(packet.data.size in 1..65_536 && packet.durationUs in 1..120_000) { "invalid Opus output" }
                    require(packets.size < 128) { "Opus output queue overflow" }
                    packets += packet
                    emittedUs += packet.durationUs
                }
                eos = eos || output.end
                if (eos) return true
            }
            error("Opus output failed to quiesce")
        }
        while (true) {
            checkActive()
            if (codec.offer(pcm, samples * 1_000_000 / 48_000, end)) break
            check(!drain()) { "unexpected Opus EOS" }
            pause(1)
        }
        samples += pcm.size
        ended = end
        if (end) {
            while (!drain()) { checkActive(); pause(1) }
        } else {
            check(!drain()) { "unexpected Opus EOS" }
            check(samples * 1_000_000 / 48_000 - emittedUs < 2_000_000) { "Opus output stalled" }
        }
        return packets
    }
    override fun close() { if (!closed) { closed = true; codec.close() } }
}

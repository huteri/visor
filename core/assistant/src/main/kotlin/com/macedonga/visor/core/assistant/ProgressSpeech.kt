package com.macedonga.visor.core.assistant

import kotlinx.coroutines.async

/** Small incremental blocks, one encoder for the entire interaction. */
internal interface ProgressEncoder : AutoCloseable {
    suspend fun encode(pcm: ShortArray, end: Boolean = false): List<OpusPacket>
}
internal interface ProgressSink {
    suspend fun start()
    suspend fun audio(packet: OpusPacket)
    suspend fun end()
    suspend fun keepalive()
}
internal data class ProgressResult(val reply: Reply, val modelMs: Long, val audioMs: Long)
internal class ProgressSpeech(
    private val now: () -> Long,
    private val pause: suspend (Long) -> Unit,
    private val synthesize: suspend (String) -> ShortArray,
    private val encoder: () -> ProgressEncoder,
    private val sink: ProgressSink,
) {
    suspend fun run(lease: DiagnosticLease, acknowledgement: String, progress: String, model: suspend () -> Reply): ProgressResult =
        kotlinx.coroutines.withTimeout(BUDGET_MS) {
            kotlinx.coroutines.coroutineScope {
                val started = now()
                val answer = async { runCatching { lease.prepare { model() } } }
                var preparation: kotlinx.coroutines.Deferred<ShortArray>? = async { lease.prepare { synthesize(acknowledgement) } }
                var finalQueued = false
                var finalPlaying = false
                var reply: Reply? = null
                var modelMs = 0L
                var pcm = ShortArray(0)
                var offset = 0
                var nextProgress = started + 15_000
                var nextKeepalive = started + 3_000
                var opened = false
                var totalEmittedUs = 0L
                var codec: ProgressEncoder? = null
                var anchorMs = -1L
                var timelineUs = 0L
                suspend fun emit(packets: List<OpusPacket>) {
                    for (packet in packets) {
                        if (anchorMs < 0) {
                            anchorMs = now()
                            timelineUs = 0L
                        } else {
                            val targetMs = anchorMs + (timelineUs + 999) / 1000
                            val wait = targetMs - now()
                            if (wait > 0) {
                                pause(wait)
                            } else if (-wait >= STALL_THRESHOLD_MS) {
                                anchorMs = now()
                                timelineUs = 0L
                            }
                        }
                        lease.check()
                        val sentAt = now()
                        lease.send { sink.audio(packet) }
                        totalEmittedUs += packet.durationUs
                        timelineUs += packet.durationUs
                        // A long transport write rebases the timeline rather than bursting overdue packets.
                        if (now() - sentAt >= STALL_THRESHOLD_MS) {
                            anchorMs = now()
                            timelineUs = 0L
                        }
                    }
                }
                var primaryFailure: Throwable? = null
                try {
                    while (true) {
                        lease.check()
                        if (answer.isCompleted && reply == null) {
                            reply = answer.await().getOrThrow()
                            modelMs = now() - started
                            preparation?.cancel()
                            lease.check()
                            preparation = async { lease.prepare { synthesize(checkNotNull(reply).text) } }
                            finalQueued = true
                        }
                        if (offset >= pcm.size) {
                            if (finalPlaying) {
                                emit(checkNotNull(codec).encode(ShortArray(0), true))
                                val left = if (anchorMs >= 0) (anchorMs + (timelineUs + 999) / 1000) - now() else 0L
                                if (left > 0) pause(left)
                                lease.send { sink.end() }
                                return@coroutineScope ProgressResult(checkNotNull(reply), modelMs, totalEmittedUs / 1000)
                            }
                            if (preparation?.isCompleted == true) {
                                pcm = checkNotNull(preparation).await()
                                require(pcm.isNotEmpty() && pcm.size < 48_000 * 25) { "speech PCM out of bounds" }
                                lease.check()
                                offset = 0
                                preparation = null
                                finalPlaying = finalQueued
                            } else if (preparation == null && reply == null && now() >= nextProgress) {
                                // No backlog, and never start stale progress after a ready final.
                                nextProgress = now() + 15_000
                                preparation = async { lease.prepare { synthesize(progress) } }
                            }
                        }
                        if (!opened && pcm.isNotEmpty()) {
                            lease.send { sink.start() }
                            codec = encoder()
                            opened = true
                        }
                        if (opened) {
                            val block = ShortArray(960)
                            val count = minOf(block.size, pcm.size - offset)
                            if (count > 0) { pcm.copyInto(block, 0, offset, offset + count); offset += count }
                            val packets = checkNotNull(codec).encode(block)
                            lease.check()
                            emit(packets)
                            // A codec may buffer the first blocks, but must not get fed an unbounded lead.
                            if (packets.isEmpty()) pause(20)
                        } else {
                            if (now() >= nextKeepalive) {
                                lease.send { sink.keepalive() }
                                nextKeepalive = now() + 3_000
                            }
                            pause(20)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                } catch (t: Throwable) {
                    primaryFailure = t
                    throw t
                } finally {
                    preparation?.cancel()
                    answer.cancel()
                    if (codec != null) {
                        try {
                            codec.close()
                        } catch (closeError: Throwable) {
                            if (primaryFailure != null) {
                                primaryFailure.addSuppressed(closeError)
                            } else {
                                throw closeError
                            }
                        }
                    }
                }
            }
        }

    companion object {
        const val BUDGET_MS = 120_000L
        const val STALL_THRESHOLD_MS = 100L
    }
}

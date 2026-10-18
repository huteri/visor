package com.macedonga.visor.core.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout

internal const val SPOKEN_PROGRESS_TURN_MS = 300_000L
internal const val SPOKEN_PROGRESS_INTERVAL_MS = 40_000L

internal class ProgressNetworkBudget : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<ProgressNetworkBudget>
}
internal fun progressReadTimeout(context: kotlin.coroutines.CoroutineContext): Int =
    if (context[ProgressNetworkBudget] != null) SPOKEN_PROGRESS_TURN_MS.toInt() else 45_000

internal object SpokenProgressPolicy {
    fun eligible(config: AssistantConfig, live: Boolean, session: Boolean): Boolean =
        config.enabled && config.spokenProgress && !live && !session
    fun startMs(timeoutS: Long): Long = minOf(SPOKEN_PROGRESS_INTERVAL_MS, timeoutS * 1000 - 10_000).coerceAtLeast(0)
}

internal interface ProgressEncoder {
    fun push(pcm: ShortArray, end: Boolean = false): List<OpusPacket>
    fun close()
}
internal interface ProgressSink {
    suspend fun open()
    suspend fun audio(packet: OpusPacket)
    suspend fun finish(text: String)
}
internal data class ProgressResult(val text: String, val streamed: Boolean, val durationMs: Long = 0)

/** One model request, one bounded PCM clip at a time, one codec and response stream. */
internal class SpokenProgress(
    private val nowMs: () -> Long,
    private val owned: () -> Boolean,
    private val synthesize: suspend (String) -> ShortArray,
    private val encoder: () -> ProgressEncoder,
    private val sink: ProgressSink,
    private val note: (String) -> Unit = {},
    private val canStream: Boolean = true,
    private val normalFinal: suspend (String) -> Long = { 0L },
    private val nativeFinal: (suspend (String) -> Unit)? = null,
) {
    private suspend fun normal(text: String): ProgressResult = ProgressResult(text, false, normalFinal(text))
    suspend fun run(started: Long, timeoutS: Long, phrase: String, model: suspend () -> String): ProgressResult = coroutineScope {
        val remaining = SPOKEN_PROGRESS_TURN_MS - (nowMs() - started)
        check(remaining > 0) { "Spoken progress: whole-turn deadline expired" }
        withTimeout(remaining) {
            val work = async { model() }
            val owner = currentCoroutineContext().job
            val guard = launch {
                while (true) {
                    if (!owned()) { owner.cancel(CancellationException("Spoken progress: turn ownership lost")); return@launch }
                    delay(10)
                }
            }
            try {
                if (!canStream) {
                    note("Spoken progress unavailable: no interaction-owned stream id; using normal final")
                    return@withTimeout normal(work.await())
                }
                val target = started + SpokenProgressPolicy.startMs(timeoutS)
                val early = (target - nowMs() - 15_000).coerceAtLeast(0)
                select<Unit> { work.onAwait { }; onTimeoutCompat(early) { } }
                if (work.isCompleted) return@withTimeout normal(work.await())
                val progress = async { runCatching { withTimeout(15_000) { synthesize(phrase) } } }
                try {
                    select<Unit> { work.onAwait { }; onTimeoutCompat((target - nowMs()).coerceAtLeast(0)) { } }
                    if (work.isCompleted) { progress.cancelAndJoin(); return@withTimeout normal(work.await()) }
                    val clip = if (progress.isCompleted) progress.await().getOrElse { error ->
                        note("Spoken progress unavailable before stream: ${error.message}; using normal final")
                        null
                    } else {
                        note("Spoken progress unavailable before stream: preparation missed due time; using normal final")
                        null
                    }
                    if (clip == null || work.isCompleted) { progress.cancelAndJoin(); return@withTimeout normal(work.await()) }
                    progress.cancelAndJoin()
                    if (!owned()) throw CancellationException("Spoken progress: ownership lost before opening")
                    val codec = try { encoder() } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        note("Spoken progress unavailable before stream: codec ${error.message}; using normal final")
                        return@withTimeout normal(work.await())
                    }
                    var failure: Throwable? = null
                    try {
                        sink.open()
                        var dueUs = nowMs() * 1000
                        var durationUs = 0L
                        suspend fun send(pcm: ShortArray, end: Boolean = false, progressPacket: Boolean = false) {
                            currentCoroutineContext().ensureActive()
                            if (!owned()) throw CancellationException("Spoken progress: ownership lost")
                            for (packet in codec.push(pcm, end)) {
                                val nowUs = nowMs() * 1000
                                // Long scheduling stalls must not release a queued burst.
                                if (nowUs - dueUs > 100_000) dueUs = nowUs
                                val waitUs = dueUs - nowUs
                                if (waitUs > 0) delay((waitUs + 999) / 1000)
                                currentCoroutineContext().ensureActive()
                                if (!owned()) throw CancellationException("Spoken progress: ownership lost")
                                if (progressPacket && work.isCompleted) {
                                    dueUs += packet.durationUs
                                    continue
                                }
                                sink.audio(packet)
                                dueUs += packet.durationUs
                                durationUs += packet.durationUs
                            }
                        }
                        suspend fun speech(pcm: ShortArray, progressClip: Boolean = false) {
                            require(pcm.size <= 48_000 * 20) { "Spoken progress: speech clip exceeds 20 seconds" }
                            var at = 0
                            while (at < pcm.size) {
                                if (progressClip && work.isCompleted) break
                                val end = minOf(at + 960, pcm.size)
                                send(pcm.copyOfRange(at, end), progressPacket = progressClip)
                                at = end
                            }
                        }
                        speech(clip, true)
                        val silence = ShortArray(960)
                        var nextProgress = target + SPOKEN_PROGRESS_INTERVAL_MS
                        var pending: Deferred<Result<ShortArray>>? = null
                        try {
                            while (!work.isCompleted) {
                                if (dueUs >= nextProgress * 1000 && nowMs() < nextProgress) {
                                    delay(nextProgress - nowMs())
                                }
                                val now = nowMs()
                                if (now >= nextProgress) {
                                    // Never queue missed updates or wait for a late synthesis.
                                    val ready = pending?.takeIf { it.isCompleted }?.await()?.getOrNull()
                                    pending?.cancelAndJoin()
                                    pending = null
                                    val onTime = now - nextProgress < 100
                                    do { nextProgress += SPOKEN_PROGRESS_INTERVAL_MS } while (nextProgress <= now)
                                    if (onTime && ready != null && !work.isCompleted) speech(ready, true)
                                } else if (pending == null && now >= nextProgress - 15_000) {
                                    pending = async { runCatching { withTimeout(15_000) { synthesize(phrase) } } }
                                }
                                if (!work.isCompleted) { send(silence, progressPacket = true); delay(1) }
                            }
                        } finally {
                            pending?.cancelAndJoin()
                        }
                        val final = work.await()
                        // Segments limit TTS/PCM allocation; every character remains ordered.
                        for (part in if (nativeFinal == null) final.chunked(240) else emptyList()) {
                            val audio = async { synthesize(part) }
                            while (!audio.isCompleted) { send(silence); delay(1) }
                            speech(audio.await())
                        }
                        send(ShortArray(0), true)
                        val waitUs = dueUs - nowMs() * 1000
                        if (waitUs > 0) delay((waitUs + 999) / 1000)
                        if (!owned()) throw CancellationException("Spoken progress: ownership lost at completion")
                        sink.finish(final)
                        currentCoroutineContext().ensureActive()
                        if (!owned()) throw CancellationException("Spoken progress: ownership lost after stream termination")
                        nativeFinal?.invoke(final)
                        ProgressResult(final, true, durationUs / 1000)
                    } catch (error: Throwable) {
                        failure = error
                        throw error
                    } finally {
                        try { codec.close() } catch (cleanup: Throwable) {
                            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                        }
                    }
                } finally { progress.cancelAndJoin() }
            } finally {
                guard.cancel()
                work.cancel()
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun <R> kotlinx.coroutines.selects.SelectBuilder<R>.onTimeoutCompat(ms: Long, block: suspend () -> R) =
    onTimeout(ms) { block() }

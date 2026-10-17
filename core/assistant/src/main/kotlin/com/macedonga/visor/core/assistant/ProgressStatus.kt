package com.macedonga.visor.core.assistant

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** One auxiliary phrase at a time, no queue, and no output after the main request finishes. */
internal class ProgressStatus(
    private val lease: DiagnosticLease,
    private val now: () -> Long,
    private val write: suspend (ActivitySnapshot, Long) -> String,
    private val failed: (ProgressStage, Throwable) -> Unit = { _, _ -> },
) {
    @Volatile private var snapshot = ActivitySnapshot()
    private val running = AtomicBoolean(true)
    private val flight = Mutex()
    private val started = now()
    fun observe(value: ActivitySnapshot) { if (running.get()) snapshot = value }
    fun stop() { running.set(false) }
    companion object {
        // A measured metadata-only Hermes response took six seconds; stay below the 15-second cadence.
        const val STATUS_TIMEOUT_MS = 8_000L
    }
    suspend fun pcm(synthesize: suspend (String) -> ShortArray): ShortArray {
        val text = next() ?: return ShortArray(960)
        try {
            val pcm = withTimeoutOrNull(4_000) { lease.prepare { synthesize(text) } }
            lease.check()
            // Never let the outer text segmenter retry/split the private progress marker.
            return if (running.get() && pcm != null && pcm.isNotEmpty() && pcm.size < 48_000 * 25) pcm else ShortArray(960)
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            lease.check()
            return ShortArray(960)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed(ProgressStage.SYNTHESIS, error)
            return ShortArray(960)
        }
    }
    suspend fun next(): String? {
        if (!running.get() || !flight.tryLock()) return null
        try {
            lease.check()
            val phrase = withTimeoutOrNull(STATUS_TIMEOUT_MS) { write(snapshot, now() - started) }
            lease.check()
            return phrase?.takeIf { running.get() }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            // An auxiliary writer's own deadline is not the owning turn's cancellation.
            lease.check()
            return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed(ProgressStage.STATUS, error)
            return null
        } finally { flight.unlock() }
    }
}

internal enum class ProgressStage { START, PHOTO, MODEL, STATUS, SYNTHESIS, DELIVERY }
internal fun progressFailure(stage: ProgressStage, error: Throwable): String {
    val (reason, kind) = when (error) {
        is DiagnosticWav.SpeechTooLong -> "speech-duration-limit" to "SpeechTooLong"
        is IllegalArgumentException -> "invalid-argument" to "IllegalArgumentException"
        is IllegalStateException -> "invalid-state" to "IllegalStateException"
        is java.io.IOException -> "io" to "IOException"
        is CancellationException -> "cancelled" to "CancellationException"
        else -> "unexpected" to "Exception"
    }
    val files = setOf("Voice.kt", "ProgressSpeech.kt", "DiagnosticWav.kt", "BoundedSpeech.kt", "NativeProgress.kt", "ProgressTurn.kt", "Brain.kt", "ModelResponse.kt", "Assistant.kt")
    val frame = error.stackTrace.firstOrNull { it.fileName in files && it.lineNumber >= 0 }
    return "stage=${stage.name.lowercase()} reason=$reason class=$kind frame=${frame?.let { "${it.fileName}:${it.lineNumber}" } ?: "unavailable"}"
}

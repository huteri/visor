package com.macedonga.visor.core.assistant

import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Keep the existing AwaitingResponse cadence; no speech or end marker before the target. */
internal suspend fun awaitDiagnosticDelay(
    lease: DiagnosticLease, pause: suspend (Long) -> Unit, keepalive: suspend () -> Unit,
) {
    var next = 3_000L
    while (lease.elapsedMs < 25_000L) {
        lease.check()
        val target = minOf(next, 25_000L)
        val left = target - lease.elapsedMs
        if (left > 0) pause(left)
        lease.check()
        if (target < 25_000L) lease.send { keepalive() }
        next += 3_000L
    }
    lease.check()
}

/** A single PCM timeline, encoded once: no fabricated Opus silence or concatenated encoder resets. */
internal fun diagnosticPcm(first: ShortArray, second: ShortArray): ShortArray {
    val secondAt = 48_000 * 25
    require(first.size < secondAt) { "diagnostic first speech exceeds 25 seconds" }
    require(second.size < 48_000 * 10) { "diagnostic second speech exceeds 10 seconds" }
    return ShortArray(secondAt + second.size).also {
        first.copyInto(it)
        second.copyInto(it, secondAt)
    }
}

/** The normal stream framing and pacing, with a revocable lease before every write. */
internal suspend fun diagnosticAudio(
    packets: List<OpusPacket>, lease: DiagnosticLease,
    now: () -> Long, pause: suspend (Long) -> Unit,
    audio: suspend (ByteArray, Boolean) -> Unit,
    varm: suspend () -> Unit, end: suspend () -> Unit,
    note: (String) -> Unit = {},
) {
    require(packets.isNotEmpty()) { "no diagnostic Opus packets" }
    val started = now()
    var dueUs = 0L
    var second = false
    for ((index, chunk) in packets.chunked(2).withIndex()) {
        lease.check()
        val frame = chunk.fold(ByteArray(0)) { acc, p ->
            acc + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(p.data.size).array() + p.data
        }
        lease.send { audio(frame, index == 0) }
        if (index == 0) {
            note("audio-first")
            lease.send { varm() }
        }
        if (!second && dueUs + chunk.sumOf { it.durationUs } > 25_000_000L) {
            second = true
            note("audio-second")
        }
        dueUs += chunk.sumOf { it.durationUs }
        val wait = dueUs / 1000 - (now() - started)
        if (wait > 0) pause(wait)
    }
    lease.send { end() }
    note("audio-end")
}

/** Generation-bound ownership also rejects work queued before a new microphone/interaction. */
internal class DiagnosticLifetime {
    private var generation = 0L
    private var closed = false
    private val completed = mutableSetOf<String>()
    private var ids = emptySet<String>()
    private var active: DiagnosticLease? = null
    private var note: (String) -> Unit = {}
    private var cancel: () -> Unit = {}

    @Synchronized fun ticket(): Long = generation

    @Synchronized fun begin(
        ticket: Long, interactions: Set<String>, now: () -> Long,
        onNote: (String) -> Unit = {}, onCancel: () -> Unit = {},
    ): DiagnosticLease? {
        if (closed || ticket != generation || active != null || interactions.any { it in completed }) return null
        val lease = DiagnosticLease(now) { ticket == ticket() }
        ids = interactions
        active = lease
        note = onNote
        cancel = onCancel
        return lease
    }

    @Synchronized fun invalidate(reason: String) {
        if (reason == "link-replaced" || reason == "channel-closed") closed = true
        generation++
        active?.let { note(reason); it.revoke(); cancel() }
        active = null
        ids = emptySet()
        note = {}
        cancel = {}
    }

    @Synchronized fun remoteComplete(interaction: String) {
        completed.add(interaction)
        if (interaction in ids) invalidate("remote-complete")
    }
}

/** Every diagnostic write and late synthesis result must still own the original interaction. */
internal class DiagnosticLease(private val now: () -> Long, private val current: () -> Boolean) {
    private val started = now()
    @Volatile private var revoked = false
    val elapsedMs: Long get() = now() - started
    fun revoke() { revoked = true }

    suspend fun check() {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (revoked || !current() || elapsedMs >= BUDGET_MS) throw kotlinx.coroutines.CancellationException("diagnostic no longer active")
    }

    suspend fun <T> prepare(block: suspend () -> T): T {
        check()
        val result = block()
        check()
        return result
    }

    suspend fun send(block: suspend () -> Unit) { prepare(block) }

    companion object { const val BUDGET_MS = 60_000L }
}

/** Explicit voice-only opt-in; never applied to phone chat. */
internal object TimeoutDiagnostic {
    enum class Mode { DELAYED, STREAMING }

    fun recognize(transcript: String): Mode? {
        val command = transcript.trim().replace(wake, "").trim().trimEnd('.', '!', '?').trim()
        return when {
            command.equals("test delayed answer", ignoreCase = true) -> Mode.DELAYED
            command.equals("test streaming answer", ignoreCase = true) -> Mode.STREAMING
            else -> null
        }
    }

    private val wake = Regex("^(?:(?:hey|hi|ehi|ei|ok|okay)[ ,]+)?(?:meta|mata|metta|matta|meda)(?:[ ,]+)", RegexOption.IGNORE_CASE)
}

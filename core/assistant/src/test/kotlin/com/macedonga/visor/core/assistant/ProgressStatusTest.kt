package com.macedonga.visor.core.assistant

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProgressStatusTest {
    @Test fun oversizedOrFailedAuxiliaryPcmIsSilentWithoutMarkerRetry() = runBlocking {
        for (mode in listOf("oversized", "exception", "empty")) {
            val status = ProgressStatus(DiagnosticLease({ 0L }, { true }, 120_000), { 0L }, { _, _ -> "safe update" })
            var calls = 0
            val pcm = status.pcm { text ->
                calls++
                assertEquals("safe update", text)
                when (mode) {
                    "oversized" -> ShortArray(48_000 * 25)
                    "empty" -> ShortArray(0)
                    else -> throw DiagnosticWav.SpeechTooLong()
                }
            }
            assertEquals(1, calls)
            assertEquals(960, pcm.size)
            assertTrue(pcm.all { it == 0.toShort() })
        }
    }

    @Test fun phoneModeGeneratesFromLatestSnapshotAtCadenceAndStopsBeforeFinal() = runBlocking {
        var ms = 0L
        var requests = 0
        var originalRequests = 0
        lateinit var source: ProgressStatus
        val spoken = mutableListOf<String>()
        val life = DiagnosticLifetime()
        ProgressTurn({ ms }).run(life, life.ticket(), setOf("request"), "owned-stream",
            controller = { lease, stream ->
                source = ProgressStatus(lease, { ms }, { snapshot, elapsed ->
                    requests++
                    assertTrue(elapsed >= 15_000)
                    assertEquals(listOf(ActivityCount(ActivityCategory.SEARCH, 1, 0)), snapshot.activity)
                    "A search is still running."
                })
                val wire = ProgressWire(lease, "request", "question", "ack", stream, 1, 2, { ms }, { 0L }, { ms += it; yield() }, { _, _, _ -> })
                ProgressSpeech({ ms }, { ms += it; yield() }, { text ->
                    if (text == "private-marker") source.pcm { spoken += it; ShortArray(960) { 1 } }
                    else { spoken += text; ShortArray(960) { 1 } }
                }, { object : ProgressEncoder {
                    override suspend fun encode(pcm: ShortArray, end: Boolean) = if (end) emptyList() else listOf(OpusPacket(byteArrayOf(0), 20_000))
                    override fun close() = Unit
                } }, wire)
            }, acknowledgement = "ack", progress = "private-marker", model = {
                originalRequests++
                source.observe(ActivitySnapshot(listOf(ActivityCount(ActivityCategory.SEARCH, 1, 0))))
                try { while (ms < 20_000) yield(); Reply("actual final") } finally { source.stop() }
            }, completed = { assertEquals("actual final", it.reply.text) })
        assertEquals(1, requests)
        assertEquals(1, originalRequests)
        assertEquals(listOf("ack", "A search is still running.", "actual final"), spoken)
        assertNull(source.next())
    }

    @Test fun nestedAuxiliaryTimeoutIsQuietAndDoesNotCancelTheMainTurn() = runBlocking {
        val lease = DiagnosticLease({ 0L }, { true }, 120_000)
        val status = ProgressStatus(lease, { 0L }, { _, _ -> withTimeout(1) { awaitCancellation() } })
        assertNull(status.next())
        currentCoroutineContext().ensureActive()
    }

    @Test fun oneFlightHasNoQueueAndLateOutputIsDiscardedOnFinal() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var calls = 0
        val status = ProgressStatus(DiagnosticLease({ 0L }, { true }, 120_000), { 0L }, { _, _ ->
            calls++; entered.complete(Unit); released.await(); "late"
        })
        val first = async { status.next() }
        entered.await()
        assertNull(status.next())
        status.stop()
        released.complete(Unit)
        assertNull(first.await())
        assertNull(status.next())
        assertEquals(1, calls)
    }

    @Test fun leaseRevocationRejectsLateAuxiliaryResults() = runBlocking {
        val lease = DiagnosticLease({ 0L }, { true }, 120_000)
        val status = ProgressStatus(lease, { 0L }, { _, _ -> lease.revoke(); "late" })
        try { status.next(); fail("revoked lease") } catch (_: CancellationException) { }
    }

    @Test fun safeFailureNeverIncludesMessagesOrArbitraryFrames() {
        val error = IllegalArgumentException("synthetic private transcript and key")
        error.stackTrace = arrayOf(StackTraceElement("secret", "secret", "secret", 1), StackTraceElement("com.macedonga.visor.core.assistant.Voice", "speechPcm", "Voice.kt", 123))
        assertEquals("stage=synthesis reason=invalid-argument class=IllegalArgumentException frame=Voice.kt:123", progressFailure(ProgressStage.SYNTHESIS, error))
    }
}

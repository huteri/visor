package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.launch

class TimeoutDiagnosticTest {
    @Test fun `remote completion before diagnostic attachment rejects its request or microphone alias`() {
        for (completed in listOf("request", "microphone")) {
            val lifetime = DiagnosticLifetime()
            val ticket = lifetime.ticket()
            lifetime.remoteComplete(completed)
            assertNull(lifetime.begin(ticket, setOf("request", "microphone"), { 0L }))
        }
    }

    @Test fun `coroutine cancellation rejects a noncancellable late TTS callback`() = kotlinx.coroutines.runBlocking {
        val late = kotlinx.coroutines.CompletableDeferred<String>()
        var writes = 0
        val job = launch {
            val lease = DiagnosticLease({ 0L }, { true })
            lease.prepare { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { late.await() } }
            lease.send { writes++ }
        }
        kotlinx.coroutines.yield()
        job.cancel()
        late.complete("late audio")
        job.join()
        assertEquals(0, writes)
    }

    @Test fun `elapsed budget expires a stream without writing its end`() = kotlinx.coroutines.runBlocking {
        var ms = 0L
        var writes = 0
        val lease = DiagnosticLease({ ms }, { true })
        try {
            diagnosticAudio(List(1300) { OpusPacket(byteArrayOf(0), 20_000) }, lease,
                now = { ms }, pause = { ms = 60_000L },
                audio = { _, _ -> writes++ }, varm = { writes++ }, end = { fail("expired stream cannot finish") })
            fail("expired")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(2, writes)
    }

    @Test fun `link replacement closure new interaction and aliases invalidate queued tickets`() = kotlinx.coroutines.runBlocking {
        for (reason in listOf("link-replaced", "channel-closed", "new-interaction")) {
            val lifetime = DiagnosticLifetime()
            val ticket = lifetime.ticket()
            val lease = lifetime.begin(ticket, setOf("request", "microphone"), { 0L })!!
            lifetime.invalidate(reason)
            assertNull(lifetime.begin(ticket, setOf("late"), { 0L }))
            if (reason != "new-interaction") assertNull("closed link must reject even a fresh callback", lifetime.begin(lifetime.ticket(), setOf("late"), { 0L }))
            try { lease.send { fail("old traffic") }; fail("old lease") } catch (_: kotlinx.coroutines.CancellationException) { }
        }
        val lifetime = DiagnosticLifetime()
        val lease = lifetime.begin(lifetime.ticket(), setOf("request", "microphone"), { 0L })!!
        lifetime.remoteComplete("unrelated")
        lease.check()
        lifetime.remoteComplete("microphone")
        try { lease.check(); fail("session alias must close request") } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun `delayed probe sends only normal keepalives until twenty five seconds`() = kotlinx.coroutines.runBlocking {
        var ms = 0L
        val lease = DiagnosticLease({ ms }, { true })
        val keepalives = mutableListOf<Long>()
        awaitDiagnosticDelay(lease, pause = { ms += it }, keepalive = { keepalives += ms })
        assertEquals(25_000L, ms)
        assertEquals((3_000L..24_000L step 3_000L).toList(), keepalives)
    }

    @Test fun `delayed cancellation before answer prohibits all later keepalives`() = kotlinx.coroutines.runBlocking {
        var ms = 0L
        val lease = DiagnosticLease({ ms }, { true })
        var sent = 0
        try {
            awaitDiagnosticDelay(lease, pause = { ms += it; lease.revoke() }, keepalive = { sent++ })
            fail("cancelled")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(0, sent)
    }

    @Test fun `PCM has encoded silence before second speech at exactly twenty five seconds`() {
        val first = shortArrayOf(7, 8)
        val second = shortArrayOf(9, 10)
        val pcm = diagnosticPcm(first, second)
        assertEquals(1_200_002, pcm.size)
        assertArrayEquals(first, pcm.copyOfRange(0, 2))
        assertTrue(pcm.copyOfRange(2, 1_200_000).all { it == 0.toShort() })
        assertArrayEquals(second, pcm.copyOfRange(1_200_000, pcm.size))
    }

    @Test fun `one paced stream marks first second and exactly one end`() = kotlinx.coroutines.runBlocking {
        var ms = 0L
        val events = mutableListOf<Pair<String, Long>>()
        // Synthetic packet contents: pacing tests do not assert codec validity.
        val packets = List(1300) { OpusPacket(byteArrayOf(0), 20_000) }
        diagnosticAudio(packets, DiagnosticLease({ ms }, { true }),
            now = { ms }, pause = { ms += it },
            audio = { _, first -> if (first) events += "audio" to ms },
            varm = { events += "varm" to ms },
            end = { events += "end" to ms },
            note = { events += it to ms })
        assertEquals(1, events.count { it.first == "end" })
        assertEquals(1, events.count { it.first == "varm" })
        assertEquals(0L, events.first { it.first == "audio-first" }.second)
        assertEquals(25_000L, events.first { it.first == "audio-second" }.second)
        assertEquals(26_000L, events.first { it.first == "end" }.second)
    }

    @Test fun `stream cancellation emits no later audio varm or end`() = kotlinx.coroutines.runBlocking {
        var ms = 0L
        val lease = DiagnosticLease({ ms }, { true })
        var writes = 0
        try {
            diagnosticAudio(List(1300) { OpusPacket(byteArrayOf(0), 20_000) }, lease,
                now = { ms }, pause = { ms += it; lease.revoke() },
                audio = { _, _ -> writes++ }, varm = { writes++ },
                end = { fail("cancelled stream must not end") })
            fail("cancelled")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(2, writes)
    }

    @Test fun `lease enforces diagnostic only budget including elapsed clock jumps`() = kotlinx.coroutines.runBlocking {
        var clock = 0L
        val lease = DiagnosticLease({ clock }, { true })
        clock = 59_999
        lease.check()
        clock = 60_000
        try { lease.check(); fail("expired lease") } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun `revoked replaced and cancelled leases reject late preparation callbacks`() = kotlinx.coroutines.runBlocking {
        for (reason in listOf("revoke", "replacement", "cancellation")) {
            var current = true
            val lease = DiagnosticLease({ 0L }, { current })
            var sent = false
            try {
                lease.prepare {
                    if (reason == "revoke") lease.revoke()
                    if (reason == "replacement") current = false
                    if (reason == "cancellation") throw kotlinx.coroutines.CancellationException()
                    "late audio"
                }
                lease.send { sent = true }
                fail("late preparation must be rejected")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertFalse(reason, sent)
        }
    }

    @Test fun `only exact spoken commands opt in including the existing wake word variants`() {
        assertEquals(TimeoutDiagnostic.Mode.DELAYED, TimeoutDiagnostic.recognize("test delayed answer"))
        assertEquals(TimeoutDiagnostic.Mode.STREAMING, TimeoutDiagnostic.recognize("Hey Meta, test streaming answer."))
        for (wake in listOf("hi mata", "ehi metta", "ei matta", "okay meda", "meta")) {
            assertEquals(TimeoutDiagnostic.Mode.DELAYED, TimeoutDiagnostic.recognize("$wake test delayed answer!"))
        }
        for (normal in listOf("", "hey meta", "please test delayed answer", "test delayed answer now", "explain test streaming answer", "test streaming answers", "metadata test delayed answer")) {
            assertNull(normal, TimeoutDiagnostic.recognize(normal))
        }
    }
}

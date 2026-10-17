package com.macedonga.visor.core.assistant

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProgressSpeechTest {
    private class Harness {
        var ms = 0L
        var models = 0
        var completedAt = -1L
        val events = mutableListOf<Pair<String, Long>>()
        val spoken = mutableListOf<String>()
        val samples = mutableListOf<Short>()
        var encoders = 0
        var released = 0
        var eos = 0
        val lease = DiagnosticLease({ ms }, { true }, ProgressSpeech.BUDGET_MS)
        var onPause: suspend () -> Unit = {}
        var synth: (suspend (String) -> ShortArray)? = null
        var onAudio: suspend () -> Unit = {}
        var onEncode: () -> Unit = {}
        var packetUs = 20_000L
        fun controller() = ProgressSpeech(
            now = { ms }, pause = { ms += it; onPause(); yield() },
            synthesize = { text -> spoken += text; synth?.invoke(text) ?: ShortArray(960) { if (text == "final") 3 else 1 } },
            encoder = { encoders++; object : ProgressEncoder {
                override suspend fun encode(pcm: ShortArray, end: Boolean): List<OpusPacket> {
                    onEncode()
                    samples.addAll(pcm.toList())
                    if (end) eos++
                    return if (pcm.isEmpty()) emptyList() else listOf(OpusPacket(byteArrayOf(0), packetUs))
                }
                override fun close() { released++ }
            } },
            sink = object : ProgressSink {
                override suspend fun start() { events += "start" to ms }
                override suspend fun audio(packet: OpusPacket) { events += "audio" to ms; onAudio() }
                override suspend fun end() { events += "end" to ms }
                override suspend fun keepalive() { events += "keepalive" to ms }
            },
        )
        suspend fun model(at: Long): Reply {
            models++
            while (ms < at) yield()
            completedAt = ms
            return Reply("final", listOf("remembered"))
        }
    }

    @Test fun `long final is synthesized as bounded clips without losing text or resetting encoder`() = runBlocking {
        val h = Harness()
        val text = (1..80).joinToString(" ") { "Sentence $it." }
        val clips = mutableListOf<String>()
        h.synth = { clip ->
            if (clip != "ack") clips += clip
            // This complete answer represents well over 25 seconds; each small clip is safe.
            ShortArray(clip.length * 1_920) { 3 }
        }
        val result = h.controller().run(h.lease, "ack", "working") { Reply(text, emptyList()) }
        assertEquals(text, clips.joinToString(""))
        assertTrue(clips.size > 1)
        assertTrue(clips.all { it.length <= 160 })
        assertEquals(text.length * 1_920, h.samples.count { it == 3.toShort() })
        assertTrue(result.audioMs > 25_000)
        assertEquals(1, h.encoders)
        assertEquals(1, h.eos)
        assertEquals(1, h.released)
        assertEquals(1, h.events.count { it.first == "end" })
    }

    @Test fun `controller retries duration overflow and streams every accepted final clip once`() = runBlocking {
        val h = Harness()
        val text = "A long final speech. ".repeat(20)
        val accepted = mutableListOf<String>()
        var oversized = 0
        h.synth = { clip ->
            if (clip == "ack") ShortArray(960) else {
                if (clip.length > 40) { oversized++; throw DiagnosticWav.SpeechTooLong() }
                accepted += clip
                ShortArray(960) { 3 }
            }
        }
        val result = h.controller().run(h.lease, "ack", "working") { Reply(text) }
        assertEquals(text, accepted.joinToString(""))
        assertEquals(text, result.reply.text)
        assertTrue(oversized > 0)
        assertEquals(accepted.size * 960, h.samples.count { it == 3.toShort() })
        assertEquals(1, h.encoders)
        assertEquals(1, h.eos)
        assertEquals(1, h.events.count { it.first == "end" })
    }

    @Test fun `new turn revokes long final and cancels prefetched clip without contaminating followup`() = runBlocking {
        val h = Harness()
        val lifetime = DiagnosticLifetime()
        val firstLease = checkNotNull(lifetime.begin(lifetime.ticket(), setOf("first"), { h.ms }, budgetMs = ProgressSpeech.BUDGET_MS))
        val controller = h.controller()
        var clips = 0
        var pending = false
        var cancelled = false
        h.synth = { text ->
            if (text != "ack" && ++clips > 1) {
                pending = true
                try { awaitCancellation() } finally { cancelled = true }
            }
            ShortArray(48_000) { 6 }
        }
        h.onAudio = { if (pending) lifetime.invalidate("new-microphone") }
        try {
            controller.run(firstLease, "ack", "working") { Reply("old answer ".repeat(80), emptyList()) }
            fail("old generation must stop")
        } catch (_: CancellationException) { }
        assertTrue("prefetched synthesis must be cancelled", cancelled)
        assertEquals(0, h.events.count { it.first == "end" })
        val oldSampleCount = h.samples.size
        h.onAudio = {}
        h.synth = { ShortArray(960) { 7 } }
        val nextLease = checkNotNull(lifetime.begin(lifetime.ticket(), setOf("followup"), { h.ms }, budgetMs = ProgressSpeech.BUDGET_MS))
        val result = controller.run(nextLease, "ack", "working") { Reply("new answer", emptyList()) }
        assertEquals("new answer", result.reply.text)
        assertTrue(h.samples.drop(oldSampleCount).all { it == 7.toShort() })
        assertEquals(2, h.encoders)
        assertEquals(2, h.released)
        assertEquals(1, h.eos)
        assertEquals(1, h.events.count { it.first == "end" })
    }

    @Test fun `long final remains subject to total turn budget rather than a per clip deadline`() = runBlocking {
        val h = Harness()
        var clips = 0
        h.synth = { clips++; ShortArray(48_000 * 10) { 3 } }
        try {
            h.controller().run(h.lease, "ack", "working") { Reply("long answer ".repeat(1_000), emptyList()) }
            fail("total turn must expire")
        } catch (_: CancellationException) { }
        assertEquals(120_000L, h.ms)
        assertTrue(clips > 1)
        assertTrue("must not synthesize the entire remaining answer", clips < 20)
        assertEquals(1, h.encoders)
        assertEquals(1, h.released)
        assertEquals(0, h.eos)
        assertEquals(0, h.events.count { it.first == "end" })
    }

    @Test fun `revoked lease cannot retry synthesis for an oversized clip`() = runBlocking {
        val h = Harness()
        var attempts = 0
        h.synth = { text ->
            if (text == "ack") ShortArray(960) else {
                attempts++
                h.lease.revoke()
                ShortArray(48_000 * 25)
            }
        }
        try {
            h.controller().run(h.lease, "ack", "working") { Reply("final", emptyList()) }
            fail("revocation must cancel the turn")
        } catch (_: CancellationException) { }
        assertEquals(1, attempts)
        assertEquals(0, h.events.count { it.first == "end" })
    }

    @Test fun `cancelling stale synthesis does not suspend the ongoing silence stream`() = runBlocking {
        val h = Harness()
        var cancelling = false
        var cleaned = false
        var continuedDuringCleanup = false
        h.synth = { text ->
            if (text == "working") {
                try { awaitCancellation() }
                finally {
                    cancelling = true
                    withContext(NonCancellable) { delay(80) }
                    cleaned = true
                }
            }
            ShortArray(960) { if (text == "final") 3 else 1 }
        }
        h.onAudio = { if (cancelling && !cleaned) continuedDuringCleanup = true }
        h.controller().run(h.lease, "ack", "working") { h.model(20_000) }
        assertTrue("silence/final must continue while obsolete synthesis releases resources", continuedDuringCleanup)
        assertTrue(cleaned)
    }

    @Test fun `fast answer skips pending acknowledgement audio and every progress phrase`() = runBlocking {
        val h = Harness()
        h.controller().run(h.lease, "ack", "working") { h.model(0) }
        assertEquals(1, h.models)
        assertFalse(h.spoken.contains("working"))
        assertTrue(h.samples.isNotEmpty())
        assertTrue(h.samples.all { it == 3.toShort() })
        assertEquals(1, h.events.count { it.first == "end" })
    }

    @Test fun `truthful generic progress is synthesized at fifteen second cadence only while outstanding`() = runBlocking {
        val h = Harness()
        val times = mutableListOf<Long>()
        h.synth = { text ->
            if (text == "working") times += h.ms
            ShortArray(960) { 1 }
        }
        h.controller().run(h.lease, "ack", "working") { h.model(46_000) }
        assertEquals(3, times.size)
        assertTrue(times.zip(listOf(15_000L, 30_000L, 45_000L)).all { (actual, due) -> actual in due..(due + 40) })
        assertEquals(1, h.models)
    }

    @Test fun `final ready at progress boundary wins without speaking stale progress`() = runBlocking {
        val h = Harness()
        h.controller().run(h.lease, "ack", "working") { h.model(15_000) }
        assertEquals(listOf("ack", "final"), h.spoken)
    }

    @Test fun `pending progress synthesis is cancelled before its audio can enter encoder`() = runBlocking {
        val h = Harness()
        var cancelled = false
        h.synth = { text ->
            if (text == "working") {
                try { while (h.ms < 25_000) yield() }
                finally { cancelled = true }
            }
            ShortArray(960) { if (text == "working") 2 else if (text == "final") 3 else 1 }
        }
        h.controller().run(h.lease, "ack", "working") { h.model(20_000) }
        assertTrue(cancelled)
        assertFalse(h.samples.contains(2.toShort()))
        assertTrue(h.samples.contains(3.toShort()))
    }

    @Test fun `already started progress phrase can finish before final audio`() = runBlocking {
        val h = Harness()
        h.synth = { text ->
            ShortArray(if (text == "working") 48_000 else 960) { if (text == "working") 2 else if (text == "final") 3 else 1 }
        }
        h.controller().run(h.lease, "ack", "working") { h.model(15_200) }
        assertEquals(48_000, h.samples.count { it == 2.toShort() })
        val firstFinal = h.samples.indexOf(3.toShort())
        assertTrue(firstFinal > h.samples.lastIndexOf(2.toShort()))
    }

    @Test fun `keepalives stop once the first live audio begins`() = runBlocking {
        val h = Harness()
        h.synth = { text ->
            if (text == "ack") while (h.ms < 10_000) yield()
            ShortArray(960) { 1 }
        }
        h.controller().run(h.lease, "ack", "working") { h.model(20_000) }
        assertEquals(listOf(3_000L, 6_000L, 9_000L), h.events.filter { it.first == "keepalive" }.map { it.second })
        assertTrue(h.events.filter { it.first == "keepalive" }.all { it.second < h.events.first { e -> e.first == "audio" }.second })
    }

    @Test fun `model failure approval and EOF never synthesize a successful final or end`() = runBlocking {
        for (failure in listOf("model failed", "approval required", "EOF before final")) {
            val h = Harness()
            try {
                h.controller().run(h.lease, "ack", "working") {
                    while (h.ms < 20_000) yield()
                    error(failure)
                }
                fail("failure must propagate")
            } catch (expected: IllegalStateException) { assertEquals(failure, expected.message) }
            assertFalse(h.spoken.contains("final"))
            assertEquals(0, h.events.count { it.first == "end" })
            assertEquals(1, h.released)
        }
    }

    @Test fun `fractional millisecond packet durations never pace audio ahead of realtime`() = runBlocking {
        val h = Harness()
        h.packetUs = 2_500
        val result = h.controller().run(h.lease, "ack", "working") { h.model(100) }
        val times = h.events.filter { it.first == "audio" }.map { it.second }
        assertTrue(times.size > 10)
        assertTrue("all intervals must be either 2ms or 3ms", times.zipWithNext().all { (a, b) -> (b - a) in 2L..3L })
        assertTrue("fractional frames must alternate and include 2ms intervals", times.zipWithNext().any { (a, b) -> b - a == 2L })
        assertTrue("fractional frames must alternate and include 3ms intervals", times.zipWithNext().any { (a, b) -> b - a == 3L })
        times.forEachIndexed { i, t ->
            val audioSentMs = i * 2_500L / 1_000L
            val elapsedMs = t - times.first()
            assertTrue("cumulative elapsed $elapsedMs must never be ahead of audio duration $audioSentMs", elapsedMs >= audioSentMs)
        }
        val totalElapsed = times.last() - times.first()
        val expectedElapsed = (times.size - 1) * 2_500L / 1_000L
        assertEquals(expectedElapsed, totalElapsed)
        assertEquals(times.size * 2_500L / 1_000L, result.audioMs)
    }

    @Test fun `scheduler oversleep jitter does not accumulate cumulative drift on production controller`() = runBlocking {
        val h = Harness()
        h.onPause = { h.ms += 1 }
        h.controller().run(h.lease, "ack", "working") { h.model(200) }
        val times = h.events.filter { it.first == "audio" }.map { it.second }
        assertTrue(times.size >= 10)
        val elapsed = times.last() - times.first()
        val totalAudioMs = (times.size - 1) * 20L
        assertTrue("drift should be at most 2ms of final wake jitter, got elapsed=$elapsed vs totalAudioMs=$totalAudioMs",
            elapsed in totalAudioMs..(totalAudioMs + 2))
    }

    @Test fun `stream suspension rebases fractional packet pacing rather than bursting old audio`() = runBlocking {
        val h = Harness()
        h.packetUs = 2_500
        var jumped = false
        h.onAudio = { if (!jumped) { jumped = true; h.ms += 5_000 } }
        h.controller().run(h.lease, "ack", "working") { h.model(7_000) }
        val times = h.events.filter { it.first == "audio" }.map { it.second }
        assertTrue(times.size > 10)
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 2 })
    }

    @Test fun `incremental encoding work is included in realtime pacing without cumulative drift`() = runBlocking {
        val h = Harness()
        h.onEncode = { h.ms += 5 }
        h.controller().run(h.lease, "ack", "working") { h.model(400) }
        val times = h.events.filter { it.first == "audio" }.map { it.second }
        assertTrue(times.size > 5)
        assertTrue("encoding latency must not add to every packet interval", times.zipWithNext().all { (a, b) -> b - a == 20L })
    }

    @Test fun `stream suspension rebases pacing rather than bursting old audio`() = runBlocking {
        val h = Harness()
        var jumped = false
        h.onAudio = { if (!jumped) { jumped = true; h.ms += 5_000 } }
        h.controller().run(h.lease, "ack", "working") { h.model(7_000) }
        val times = h.events.filter { it.first == "audio" }.map { it.second }
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 20 })
    }

    @Test fun `expired final playback never emits end after a clock jump`() = runBlocking {
        val h = Harness()
        h.onAudio = { h.ms = 120_000 }
        try { h.controller().run(h.lease, "ack", "working") { h.model(0) }; fail("expired") }
        catch (_: CancellationException) { }
        assertEquals(0, h.events.count { it.first == "end" })
        assertEquals(1, h.released)
    }

    @Test fun `experimental deadline includes model and silence and never sends successful end`() = runBlocking {
        val h = Harness()
        try {
            h.controller().run(h.lease, "ack", "working") { h.model(150_000) }
            fail("must time out")
        } catch (_: CancellationException) { }
        assertEquals(120_000L, h.ms)
        assertEquals(0, h.events.count { it.first == "end" })
        assertEquals(1, h.released)
    }

    @Test fun `early speech and live silence precede real slow final on one encoder`() = runBlocking {
        val h = Harness()
        val result = h.controller().run(h.lease, "ack", "working") { h.model(32_000) }
        assertEquals("final", result.reply.text)
        assertEquals(listOf("remembered"), result.reply.remembered)
        assertEquals(1, h.models)
        assertEquals(1, h.encoders)
        assertEquals(1, h.eos)
        assertEquals(1, h.released)
        assertEquals(1, h.events.count { it.first == "start" })
        assertEquals(1, h.events.count { it.first == "end" })
        assertTrue(h.events.first { it.first == "audio" }.second < h.completedAt)
        assertTrue(h.samples.any { it == 0.toShort() })
        assertEquals(listOf("ack", "working", "working", "final"), h.spoken)
        assertTrue(h.events.last().second > 32_000)
    }

    @Test fun `controller cancellation survives and cancels siblings even if encoder close throws`() = runBlocking {
        val h = Harness()
        var closeThrew = false
        var synthCancelled = false
        h.synth = { text ->
            if (text == "working") {
                try {
                    awaitCancellation()
                } finally {
                    synthCancelled = true
                }
            }
            ShortArray(960) { 1 }
        }
        val encoderWithFailingClose = object : ProgressEncoder {
            override suspend fun encode(pcm: ShortArray, end: Boolean) =
                if (pcm.isEmpty()) emptyList() else listOf(OpusPacket(byteArrayOf(0), 20_000))
            override fun close() {
                closeThrew = true
                throw IllegalStateException("native codec stop failed")
            }
        }
        val controller = ProgressSpeech(
            now = { h.ms }, pause = { h.ms += it; yield() },
            synthesize = { text -> h.spoken += text; h.synth?.invoke(text) ?: ShortArray(960) { 1 } },
            encoder = { encoderWithFailingClose },
            sink = object : ProgressSink {
                override suspend fun start() { h.events += "start" to h.ms }
                override suspend fun audio(packet: OpusPacket) { h.events += "audio" to h.ms }
                override suspend fun end() { h.events += "end" to h.ms }
                override suspend fun keepalive() { h.events += "keepalive" to h.ms }
            }
        )

        try {
            controller.run(h.lease, "ack", "working") {
                while (h.events.none { it.first == "audio" }) yield()
                while (h.ms < 15_000) yield()
                while (!h.spoken.contains("working")) yield()
                throw CancellationException("turn explicitly cancelled")
            }
            fail("CancellationException must be thrown")
        } catch (expected: CancellationException) {
            assertEquals("turn explicitly cancelled", expected.message)
            assertTrue("encoder.close must have been invoked and thrown", closeThrew)
            assertTrue("synthesis sibling must still be cancelled", synthCancelled)
            val allSuppressed = expected.suppressedExceptions.toList() + (expected.cause?.suppressedExceptions?.toList() ?: emptyList())
            assertTrue("close failure must be attached as suppressed exception",
                allSuppressed.any { it is IllegalStateException && it.message == "native codec stop failed" })
        }
    }

    @Test fun `encoder close failure on successful path is not swallowed`() = runBlocking {
        val h = Harness()
        val encoderWithFailingClose = object : ProgressEncoder {
            override suspend fun encode(pcm: ShortArray, end: Boolean) =
                if (pcm.isEmpty()) emptyList() else listOf(OpusPacket(byteArrayOf(0), 20_000))
            override fun close() {
                throw IllegalStateException("codec close failure on success")
            }
        }
        val controller = ProgressSpeech(
            now = { h.ms }, pause = { h.ms += it; yield() },
            synthesize = { ShortArray(960) { 1 } },
            encoder = { encoderWithFailingClose },
            sink = object : ProgressSink {
                override suspend fun start() {}
                override suspend fun audio(packet: OpusPacket) {}
                override suspend fun end() {}
                override suspend fun keepalive() {}
            }
        )
        try {
            controller.run(h.lease, "ack", "working") { Reply("answer", emptyList()) }
            fail("IllegalStateException on success path must not be swallowed")
        } catch (expected: IllegalStateException) {
            assertEquals("codec close failure on success", expected.message)
        }
    }
}

package com.macedonga.visor.core.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpokenProgressTest {
    @Test fun `native final drains EOS before handoff without phone final synthesis`() = runTest {
        val events = mutableListOf<String>()
        val synthesized = mutableListOf<String>()
        val controller = SpokenProgress(
            nowMs = { testScheduler.currentTime }, owned = { true },
            synthesize = { synthesized += it; ShortArray(960) { 1 } },
            encoder = { object : ProgressEncoder {
                override fun push(pcm: ShortArray, end: Boolean): List<OpusPacket> {
                    if (end) events += "eos"
                    return listOf(OpusPacket(byteArrayOf(if (end) 9 else 1), 20_000))
                }
                override fun close() { events += "close" }
            } },
            sink = object : ProgressSink {
                override suspend fun open() { events += "open" }
                override suspend fun audio(packet: OpusPacket) { events += "audio:${packet.data[0]}" }
                override suspend fun finish(text: String) { events += "end" }
            },
            nativeFinal = { events += "native:$it" },
        )
        controller.run(0, 20, "working") { delay(11_000); "final" }
        assertTrue(synthesized.isNotEmpty())
        assertTrue(synthesized.all { it == "working" })
        assertEquals(listOf("eos", "audio:9", "end", "native:final", "close"), events.takeLast(5))
    }

    @Test fun `remote close during stream termination blocks native final`() = runTest {
        var owned = true
        var native = false
        val controller = SpokenProgress(
            nowMs = { testScheduler.currentTime }, owned = { owned },
            synthesize = { ShortArray(960) },
            encoder = { object : ProgressEncoder {
                override fun push(pcm: ShortArray, end: Boolean) = if (end) emptyList() else listOf(OpusPacket(byteArrayOf(0), 20_000))
                override fun close() = Unit
            } },
            sink = object : ProgressSink {
                override suspend fun open() = Unit
                override suspend fun audio(packet: OpusPacket) = Unit
                override suspend fun finish(text: String) { owned = false }
            }, nativeFinal = { native = true },
        )
        val job = launch { controller.run(0, 20, "working") { delay(11_000); "final" } }
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertFalse(native)
    }

    @Test fun `ownership loss cancels normal final synthesis before any delayed send`() = runTest {
        var owned = true
        var sent = false
        var canceled = false
        val controller = SpokenProgress(
            nowMs = { testScheduler.currentTime }, owned = { owned },
            synthesize = { error("not needed") }, encoder = { error("not needed") },
            sink = object : ProgressSink {
                override suspend fun open() = Unit
                override suspend fun audio(packet: OpusPacket) = Unit
                override suspend fun finish(text: String) = Unit
            }, normalFinal = {
                try { delay(2_000); sent = true; 0L } finally { canceled = true }
            },
        )
        val job = launch { controller.run(0, 55, "working") { "fast final" } }
        advanceTimeBy(100)
        owned = false
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertTrue(canceled)
        assertFalse(sent)
    }

    @Test fun `missing owned stream keeps actual final and never opens`() = runTest {
        val controller = SpokenProgress(
            nowMs = { testScheduler.currentTime }, owned = { true },
            synthesize = { error("must not synthesize progress") }, encoder = { error("must not encode") },
            sink = object : ProgressSink {
                override suspend fun open() { error("must not open") }
                override suspend fun audio(packet: OpusPacket) { error("must not send") }
                override suspend fun finish(text: String) { error("must not end") }
            }, canStream = false,
        )
        val result = controller.run(0, 20, "working") { delay(15_000); "real answer" }
        assertFalse(result.streamed)
        assertEquals("real answer", result.text)
    }

    @Test fun `late dynamic final uses one encoder and stream with paced silence`() = runTest {
        var requests = 0
        var encoders = 0
        var opens = 0
        var ends = 0
        var zeros = 0
        val speech = mutableListOf<Pair<Long, Int>>()
        val sink = object : ProgressSink {
            override suspend fun open() { opens++ }
            override suspend fun audio(packet: OpusPacket) { speech += testScheduler.currentTime to packet.data[0].toInt() }
            override suspend fun finish(text: String) { assertEquals("late final", text); ends++ }
        }
        val controller = SpokenProgress(
            nowMs = { testScheduler.currentTime }, owned = { true },
            synthesize = { text -> ShortArray(960) { if (text == "working") 1 else 2 } },
            encoder = {
                encoders++
                object : ProgressEncoder {
                    override fun push(pcm: ShortArray, end: Boolean): List<OpusPacket> {
                        if (end) return emptyList()
                        if (pcm.all { it == 0.toShort() }) zeros++
                        return listOf(OpusPacket(byteArrayOf(pcm[0].toByte()), 20_000))
                    }
                    override fun close() = Unit
                }
            }, sink = sink,
        )
        val result = controller.run(0, 55, "working") { requests++; delay(70_000); "late final" }
        assertEquals("late final", result.text)
        assertTrue(result.streamed)
        assertEquals(1, requests); assertEquals(1, encoders); assertEquals(1, opens); assertEquals(1, ends)
        assertEquals(40_000L, speech.first().first)
        assertTrue(zeros > 1000)
        assertTrue(speech.any { it.first >= 70_000 && it.second == 2 })
        assertTrue(speech.none { it.first >= 70_000 && it.second == 1 })
        assertTrue(speech.zipWithNext().filter { (a,b) -> b.first - a.first < 20 }.toString(), speech.zipWithNext().all { (a,b) -> b.first - a.first >= 20 })
    }
}

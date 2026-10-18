package com.macedonga.visor.core.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpokenProgressLifecycleTest {
    private fun encoder(duration: Long = 20_000, cleanup: () -> Unit = {}) = object : ProgressEncoder {
        override fun push(pcm: ShortArray, end: Boolean) = if (end) emptyList() else listOf(OpusPacket(byteArrayOf(pcm[0].toByte()), duration))
        override fun close() = cleanup()
    }
    private fun sink(audio: suspend (OpusPacket) -> Unit = {}, finish: () -> Unit = {}) = object : ProgressSink {
        override suspend fun open() = Unit
        override suspend fun audio(packet: OpusPacket) = audio.invoke(packet)
        override suspend fun finish(text: String) = finish.invoke()
    }
    @Test fun `final arriving during paced progress sends no later progress packet`() = runTest {
        val spoken = mutableListOf<Pair<Long, Int>>()
        val controller = SpokenProgress({testScheduler.currentTime}, {true}, { text -> ShortArray(9600) { if (text == "working") 1 else 2 } }, {encoder()}, sink(audio = { spoken += testScheduler.currentTime to it.data[0].toInt() }))
        controller.run(0, 20, "working") {delay(10_010); "answer"}
        assertTrue(spoken.none { it.first >= 10_010 && it.second == 1 })
    }
    @Test fun `ready final cancels progress before selected voice final`() = runTest {
        var canceled = false
        var normal = false
        val controller = SpokenProgress({testScheduler.currentTime}, {true}, {
            try { delay(15_000); ShortArray(960) } finally { canceled = true }
        }, {error("must not encode")}, sink(), normalFinal = { assertTrue(canceled); normal = true; 0 })
        val result = controller.run(0, 55, "working") {delay(30_000); "fast"}
        assertFalse(result.streamed)
        assertTrue(normal)
    }
    @Test fun `20 second timeout accounts question elapsed and synth time`() = runTest {
        advanceTimeBy(5_000)
        val writes = mutableListOf<Long>()
        val controller = SpokenProgress({testScheduler.currentTime}, {true}, {delay(2_000); ShortArray(960)}, {encoder()}, sink(audio = {writes += testScheduler.currentTime}))
        controller.run(0, 20, "working") {delay(10_000); "answer"}
        assertEquals(10_000L, writes.first())
    }
    @Test fun `revocation stops model and all future audio without terminal`() = runTest {
        var owned = true
        var modelCanceled = false
        var ended = false
        var writes = 0
        val controller = SpokenProgress({testScheduler.currentTime}, {owned}, {ShortArray(960)}, {encoder()}, sink(audio = {writes++}, finish = {ended = true}))
        val job = launch {controller.run(0, 20, "working") {try {delay(70_000); "late"} finally {modelCanceled = true}}}
        advanceTimeBy(12_000); runCurrent()
        owned = false
        advanceTimeBy(50); runCurrent()
        val stopped = writes
        advanceUntilIdle()
        assertTrue(job.isCancelled); assertTrue(modelCanceled); assertFalse(ended); assertEquals(stopped, writes)
    }
    @Test fun `whole turn bounded to 300 seconds cancels unfinished model`() = runTest {
        var canceled = false
        val controller = SpokenProgress({testScheduler.currentTime}, {true}, {ShortArray(960)}, {encoder()}, sink())
        try {
            controller.run(0, 55, "working") {try {delay(310_000); "late"} finally {canceled = true}}
            fail("deadline expected")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(300_000L, testScheduler.currentTime)
        assertTrue(canceled)
    }
    @Test fun `fractional packet pacing cumulative and no burst after stalled sink`() = runTest {
        val times = mutableListOf<Long>()
        val controller = SpokenProgress({testScheduler.currentTime}, {true}, {ShortArray(48_000) {1}}, {encoder(2_500)}, sink(audio = {
            times += testScheduler.currentTime
            if (times.size == 20) delay(500)
        }))
        controller.run(0, 20, "working") {delay(11_000); "final"}
        assertTrue(times[19] - times.first() in 47..49)
        assertTrue(times.zipWithNext().all { (a,b) -> b - a >= 2 })
    }
    @Test fun `primary stream exception survives failing codec cleanup`() = runTest {
        val primary = IllegalStateException("primary")
        val controller = SpokenProgress({testScheduler.currentTime}, {true}, {ShortArray(960)}, {encoder(cleanup = {error("cleanup")})}, sink(audio = {throw primary}))
        try {controller.run(0, 20, "working") {delay(70_000); "late"}; fail("failure expected")}
        catch (error: IllegalStateException) {val original = generateSequence<Throwable>(error) { it.cause }.first { it === primary }; assertSame(primary, original); assertEquals("cleanup", original.suppressed.single().message)}
    }
    @Test fun `segmented final retains full ordered text in one codec`() = runTest {
        val text = "abcdef ".repeat(100)
        val parts = mutableListOf<String>()
        var codecs = 0
        var ended = 0
        val controller = SpokenProgress({testScheduler.currentTime}, {true}, {parts += it; ShortArray(960)}, {codecs++; encoder()}, sink(finish = {ended++}))
        controller.run(0, 20, "working") {delay(11_000); text}
        assertEquals(text, parts.drop(1).joinToString(""))
        assertTrue(parts.all {it.length <= 240})
        assertEquals(1, codecs); assertEquals(1, ended)
    }
}

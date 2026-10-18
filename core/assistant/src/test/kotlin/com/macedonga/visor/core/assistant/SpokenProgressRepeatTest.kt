package com.macedonga.visor.core.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpokenProgressRepeatTest {
    private fun TestScope.controller(
        speech: MutableList<Long>, synth: suspend () -> ShortArray = { ShortArray(960) { 1 } },
        owned: () -> Boolean = { true }, finished: () -> Unit = {},
    ) = SpokenProgress({ testScheduler.currentTime }, owned, { synth() }, {
        object : ProgressEncoder {
            override fun push(pcm: ShortArray, end: Boolean) = if (end) emptyList() else listOf(OpusPacket(byteArrayOf(pcm[0].toByte()), 20_000))
            override fun close() = Unit
        }
    }, object : ProgressSink {
        override suspend fun open() = Unit
        override suspend fun audio(packet: OpusPacket) { if (packet.data[0] == 1.toByte()) speech += testScheduler.currentTime }
        override suspend fun finish(text: String) = Unit
    }, nativeFinal = { finished() })

    @Test fun `30 second wait repeats at anchored 20 40 and 60 seconds`() = runTest {
        val speech = mutableListOf<Long>()
        controller(speech).run(0, 30, "working") { delay(70_000); "final" }
        assertEquals(listOf(20_000L, 40_000L, 60_000L), speech)
    }

    @Test fun `short wait boundary keeps positive anchored cadence`() = runTest {
        val speech = mutableListOf<Long>()
        controller(speech).run(0, 11, "working") { delay(3_500); "final" }
        assertEquals(listOf(1_000L, 2_000L, 3_000L), speech)
    }

    @Test fun `20 second supported wait repeats every 10 seconds`() = runTest {
        val speech = mutableListOf<Long>()
        controller(speech).run(0, 20, "working") { delay(35_000); "final" }
        assertEquals(listOf(10_000L, 20_000L, 30_000L), speech)
    }

    @Test fun `initial synthesis missing due time falls back without late progress`() = runTest {
        val speech = mutableListOf<Long>()
        val result = controller(speech, synth = { delay(12_000); ShortArray(960) { 1 } })
            .run(0, 20, "working") { delay(90_000); "final" }
        assertFalse(result.streamed)
        assertTrue(speech.isEmpty())
    }

    @Test fun `final at 70 cancels repeat synthesis and never speaks at 80`() = runTest {
        val speech = mutableListOf<Long>()
        var calls = 0
        var canceled = false
        controller(speech, synth = {
            if (++calls == 2) try { delay(14_000) } finally { canceled = true }
            ShortArray(960) { 1 }
        }).run(0, 55, "working") { delay(70_000); "final" }
        assertEquals(listOf(40_000L), speech)
        assertTrue(canceled)
    }

    @Test fun `final beyond 120 succeeds with all seven anchored repeats before 290`() = runTest {
        val speech = mutableListOf<Long>()
        var final = false
        controller(speech, finished = { final = true }).run(0, 55, "working") { delay(290_000); "final" }
        assertEquals((40_000L..280_000L step 40_000).toList(), speech)
        assertTrue(final)
    }

    @Test fun `late revocation cancels pending synthesis and produces no later PCM or final`() = runTest {
        val speech = mutableListOf<Long>()
        var owned = true
        var calls = 0
        var canceled = false
        var final = false
        val job = launch {
            controller(speech, synth = {
                if (++calls == 6) try { delay(14_000) } finally { canceled = true }
                ShortArray(960) { 1 }
            }, owned = { owned }, finished = { final = true }).run(0, 55, "working") { delay(290_000); "final" }
        }
        advanceTimeBy(230_000); runCurrent()
        owned = false
        advanceTimeBy(20); runCurrent()
        val stopped = speech.toList()
        advanceUntilIdle()
        assertTrue(job.isCancelled); assertTrue(canceled); assertFalse(final)
        assertEquals(stopped, speech)
    }

    @Test fun `late repeat synthesis skipped without backlog while silence continues`() = runTest {
        val speech = mutableListOf<Long>()
        var calls = 0
        var canceled = false
        controller(speech, synth = {
            if (++calls == 2) try { delay(30_000) } finally { canceled = true }
            ShortArray(960) { 1 }
        }).run(0, 55, "working") { delay(130_000); "final" }
        assertEquals(listOf(40_000L, 120_000L), speech)
        assertTrue(canceled)
    }

    @Test fun `unfinished model speaks at anchored 40 and 80 seconds before native final at 90`() = runTest {
        val speech = mutableListOf<Long>()
        var models = 0
        var codecs = 0
        var opens = 0
        var finals = 0
        val controller = SpokenProgress(
            { testScheduler.currentTime }, { true }, { ShortArray(960) { 1 } },
            { codecs++; object : ProgressEncoder {
                override fun push(pcm: ShortArray, end: Boolean) = if (end) emptyList() else listOf(OpusPacket(byteArrayOf(pcm[0].toByte()), 20_000))
                override fun close() = Unit
            } }, object : ProgressSink {
                override suspend fun open() { opens++ }
                override suspend fun audio(packet: OpusPacket) { if (packet.data[0] == 1.toByte()) speech += testScheduler.currentTime }
                override suspend fun finish(text: String) = Unit
            }, nativeFinal = { finals++ },
        )
        controller.run(0, 55, "working") { models++; delay(90_000); "final" }
        assertEquals(listOf(40_000L, 80_000L), speech)
        assertEquals(1, models); assertEquals(1, codecs); assertEquals(1, opens); assertEquals(1, finals)
    }
}

package com.macedonga.visor.core.assistant

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProgressTurnTest {
    private class Fixture {
        var ms = 0L
        val life = DiagnosticLifetime()
        var ticket = life.ticket()
        var streamId = "owned-stream"
        var calls = 0
        var commits = 0
        var failures = 0
        var released = 0
        var modelCancelled = false
        var allowed = true
        val types = mutableListOf<Int?>()
        var onPause: suspend () -> Unit = {}
        var onSend: suspend (Int?) -> Unit = {}
        var synthesis: suspend (String) -> ShortArray = { ShortArray(960) { 1 } }
        var model: suspend () -> Reply = { while (ms < 50_000) yield(); Reply("final") }
        suspend fun run() {
            ProgressTurn({ ms }).run(life, ticket, setOf("request", "microphone"), streamId,
                controller = { lease, stream ->
                    val wire = ProgressWire(lease, "request", "question", "ack", stream, 1, 2,
                        { ms }, { 1000L }, { ms += it; onPause(); yield() }) { _, _, type -> types += type; onSend(type) }
                    ProgressSpeech({ ms }, { ms += it; onPause(); yield() }, synthesis,
                        { object : ProgressEncoder {
                            override suspend fun encode(pcm: ShortArray, end: Boolean) =
                                if (end) emptyList() else listOf(OpusPacket(byteArrayOf(0), 20_000))
                            override fun close() { released++ }
                        } }, wire)
                }, acknowledgement = "ack", progress = "working",
                model = { calls++; try { model() } finally { modelCancelled = !currentCoroutineContext().isActive } },
                completed = { commits++ }, failed = { failures++ }, ownership = { allowed })
        }
    }

    @Test fun `new mic request followup disable channel close and link replacement cancel actual turn work`() = runBlocking {
        for (reason in listOf("new-microphone", "new-request", "new-followup", "new-interaction", "progress-disabled", "channel-closed", "link-replaced")) {
            val f = Fixture()
            var writesAtRevoke = -1
            f.onPause = {
                if (f.ms >= 1_000 && writesAtRevoke < 0) {
                    writesAtRevoke = f.types.size
                    f.life.invalidate(reason)
                }
            }
            val job = launch { f.run() }
            job.join()
            assertTrue(reason, job.isCancelled)
            assertEquals(reason, writesAtRevoke, f.types.size)
            assertEquals(0, f.commits)
            assertEquals(0, f.failures)
            assertTrue(f.modelCancelled)
            assertEquals(1, f.released)
            if (reason in listOf("channel-closed", "link-replaced")) {
                f.ticket = f.life.ticket()
                f.run()
                assertEquals(1, f.calls)
            }
        }
    }

    @Test fun `request or microphone remote completion cancels stream and final publication`() = runBlocking {
        for (alias in listOf("request", "microphone")) {
            val f = Fixture()
            var writesAtRevoke = -1
            f.onPause = { if (f.ms >= 1_000 && writesAtRevoke < 0) { writesAtRevoke = f.types.size; f.life.remoteComplete(alias) } }
            launch { f.run() }.join()
            assertEquals(writesAtRevoke, f.types.size)
            assertEquals(0, f.commits)
            assertTrue(f.modelCancelled)
        }
    }

    @Test fun `completion before attachment and queued old generations never call model or write`() = runBlocking {
        for (alias in listOf("request", "microphone")) {
            val f = Fixture()
            f.life.remoteComplete(alias)
            f.run()
            assertEquals(0, f.calls)
            assertTrue(f.types.isEmpty())
        }
        val f = Fixture()
        f.life.invalidate("new-request")
        f.run()
        assertEquals(0, f.calls)
        assertEquals(0, f.commits)
        assertTrue(f.types.isEmpty())
    }

    @Test fun `disabled routing gate rejects queued work even before flow observer revokes its generation`() = runBlocking {
        val f = Fixture()
        f.allowed = false
        f.run()
        assertEquals(0, f.calls)
        assertEquals(0, f.commits)
        assertTrue(f.types.isEmpty())
    }

    @Test fun `missing owned stream fails closed before model speech or history final`() = runBlocking {
        val f = Fixture()
        f.streamId = ""
        try { f.run(); fail("missing owned stream") } catch (_: IllegalArgumentException) { }
        assertEquals(0, f.calls)
        assertEquals(0, f.commits)
        assertTrue(f.types.isEmpty())
    }

    @Test fun `already admitted audio write may finish but revocation rejects VARM and every later write`() = runBlocking {
        val f = Fixture()
        f.onSend = { type -> if (type == MT_STREAM_AUDIO) f.life.remoteComplete("microphone") }
        launch { f.run() }.join()
        assertEquals(MT_STREAM_AUDIO, f.types.last())
        assertEquals(0, f.types.count { it == MT_STREAM_END })
        assertEquals(0, f.commits)
        assertEquals(1, f.released)
    }

    @Test fun `late noncancellable model result cannot publish final history or end`() = runBlocking {
        val f = Fixture()
        val late = CompletableDeferred<Reply>()
        f.model = { withContext(NonCancellable) { late.await() } }
        f.onPause = { if (f.ms >= 1_000) { f.life.invalidate("new-request"); late.complete(Reply("late")) } }
        launch { f.run() }.join()
        assertEquals(0, f.commits)
        assertEquals(0, f.failures)
        assertEquals(0, f.types.count { it == MT_STREAM_END })
    }

    @Test fun `late noncancellable synthesis result cannot open old stream`() = runBlocking {
        val f = Fixture()
        val late = CompletableDeferred<ShortArray>()
        f.synthesis = { withContext(NonCancellable) { late.await() } }
        f.onPause = { if (f.ms >= 1_000) { f.life.invalidate("new-request"); late.complete(ShortArray(960)) } }
        launch { f.run() }.join()
        assertTrue(f.types.isEmpty())
        assertEquals(0, f.commits)
        assertTrue(f.modelCancelled)
    }

    @Test fun `failed backend marks failure but never a successful final`() = runBlocking {
        val f = Fixture()
        f.model = { while (f.ms < 20_000) yield(); error("synthetic EOF") }
        try { f.run(); fail("failure") } catch (_: IllegalStateException) { }
        assertEquals(1, f.failures)
        assertEquals(0, f.commits)
        assertEquals(0, f.types.count { it == MT_STREAM_END })
        assertEquals(1, f.released)
    }

    @Test fun `revocation during stream end rejects completion metadata and diary mutation`() = runBlocking {
        val f = Fixture()
        f.model = { Reply("final") }
        f.onSend = { type -> if (type == MT_STREAM_END) f.life.remoteComplete("request") }
        launch { f.run() }.join()
        assertEquals(MT_STREAM_END, f.types.last())
        assertEquals(0, f.commits)
    }

    @Test fun `unrelated remote completion does not close the owned turn`() = runBlocking {
        val f = Fixture()
        f.model = { f.life.remoteComplete("unrelated"); Reply("final") }
        f.run()
        assertEquals(1, f.commits)
    }

    @Test fun `owned real turn drives production controller and wire then commits actual reply only`() = runBlocking {
        var ms = 0L
        var commits = 0
        var calls = 0
        val types = mutableListOf<Int?>()
        val events = mutableListOf<Int>()
        val life = DiagnosticLifetime()
        ProgressTurn({ ms }).run(life, life.ticket(), setOf("request", "microphone"), "owned-stream",
            controller = { lease, stream ->
                val wire = ProgressWire(lease, "request", "question", "ack", stream, 1, 2,
                    { ms }, { 1000L }, { ms += it; yield() }) { _, bytes, type ->
                    types += type
                    if (type == MT_NET_EVENT || type == 0) events += parseNetEvent(bytes).id
                }
                ProgressSpeech({ ms }, { ms += it; yield() }, { ShortArray(960) { 1 } },
                    { object : ProgressEncoder {
                        override suspend fun encode(pcm: ShortArray, end: Boolean) =
                            if (end) emptyList() else listOf(OpusPacket(byteArrayOf(0), 20_000))
                        override fun close() = Unit
                    } }, wire)
            }, acknowledgement = "ack", progress = "working",
            model = { calls++; while (ms < 20_000) yield(); Reply("actual final", listOf("real memory")) },
            completed = { result ->
                assertEquals("actual final", result.reply.text)
                assertEquals(listOf("real memory"), result.reply.remembered)
                commits++
            })
        assertEquals(1, calls)
        assertEquals(1, commits)
        assertEquals(1, types.count { it == MT_STREAM_HEADER })
        assertEquals(1, types.count { it == MT_STREAM_AUDIO })
        assertEquals(1, types.count { it == MT_STREAM_END })
        assertEquals(listOf(71, 52, 342, 388, 53, 389, 392), events)
    }
}

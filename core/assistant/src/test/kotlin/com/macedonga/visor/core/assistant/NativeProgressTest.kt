package com.macedonga.visor.core.assistant

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeProgressTest {
    @Test fun nativeIsExplicitlyRoutedButNeverOverridesLiveAi() {
        val config = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, spokenProgress = true, nativeProgress = true)
        assertEquals("NATIVE_PROGRESS", AssistantTurnRouter.routeAnswer("hello", config, false).name)
        assertEquals(AnswerRoute.ORDINARY, AssistantTurnRouter.routeAnswer("hello", config, true))
        assertEquals(AnswerRoute.ORDINARY, AssistantTurnRouter.routeAnswer("hello", config.copy(spokenProgress = false), false))
        assertEquals(FollowupAction.SESSION_ALIAS, AssistantTurnRouter.routeFollowup("hello again", config, false))
        assertEquals(FollowupAction.PREAMBLE, AssistantTurnRouter.routeFollowup("hello again", config, true))
    }

    @Test fun interimVarmUsesSelectedVoiceAndOnlyFinalCompletesInteraction() = runBlocking {
        val events = mutableListOf<ByteArray>()
        val lease = DiagnosticLease({ 0L }, { true }, 120_000)
        val wire = NativeProgressWire(lease, "interaction", "question", "selected-synthetic-voice", {}, { bytes, _ -> events += bytes })
        wire.speak("ack", false)
        wire.speak("update", false)
        assertEquals(listOf(71, 52, 342, 53, 53), events.map { parseNetEvent(it).id })
        assertTrue(events.last().toString(Charsets.ISO_8859_1).contains("selected-synthetic-voice"))
        wire.speak("final", true)
        assertEquals(listOf(71, 52, 342, 53, 53, 53, 60, 392), events.map { parseNetEvent(it).id })
        assertThrows(IllegalStateException::class.java) { runBlocking { wire.speak("stale", false) } }
        lease.revoke()
        assertThrows(CancellationException::class.java) { runBlocking {
            NativeProgressWire(lease, "next", "question", "voice", {}, { _, _ -> fail("revoked write") }).speak("late", false)
        } }
        Unit
    }

    @Test fun ownedNativeBoundaryNeedsNoPhoneStreamAndCancelsEveryLifecycleAlias() = runBlocking {
        for (reason in listOf("new-microphone", "new-request", "new-followup", "progress-settings-changed", "channel-closed", "link-replaced", "request", "microphone", "ownership", "budget")) {
            var ms = 0L
            val life = DiagnosticLifetime()
            var allowed = true
            var calls = 0
            var commits = 0
            var cancelled = false
            val events = mutableListOf<Int>()
            var writesAtRevoke: Int? = null
            val pause: suspend (Long) -> Unit = {
                ms += it
                if (ms >= (if (reason == "budget") 120_000 else 1_000) && writesAtRevoke == null) {
                    writesAtRevoke = events.size
                    when (reason) {
                        "request", "microphone" -> life.remoteComplete(reason)
                        "ownership" -> allowed = false
                        "budget" -> Unit
                        else -> life.invalidate(reason)
                    }
                }
                yield()
            }
            val job = launch {
                ProgressTurn({ ms }).run(life, life.ticket(), setOf("request", "microphone"), "",
                    controller = { _, _ -> error("must not use phone TTS") }, acknowledgement = "ack", progress = "working",
                    model = { error("native model is supplied to native controller") }, completed = { commits++ }, ownership = { allowed },
                    native = { lease ->
                        val wire = NativeProgressWire(lease, "request", "question", "selected-voice", pause, { bytes, _ -> events += parseNetEvent(bytes).id })
                        NativeProgress({ ms }, pause).run(lease, wire, "ack", { "working" }, {
                            calls++
                            try { awaitCancellation() } finally { cancelled = true }
                        })
                    })
            }
            job.join()
            assertTrue(reason, job.isCancelled)
            assertEquals(reason, 1, calls)
            assertTrue(reason, cancelled)
            assertEquals(reason, 0, commits)
            assertEquals(reason, writesAtRevoke, events.size)
            assertFalse(reason, events.contains(EV_MWA_VOICE_INTERACTION_COMPLETE))
        }
    }

    @Test fun successfulNativeTurnPublishesOnlyTheActualFinal() = runBlocking {
        var ms = 0L
        val life = DiagnosticLifetime()
        val events = mutableListOf<Int>()
        var commits = 0
        val pause: suspend (Long) -> Unit = { ms += it; yield() }
        ProgressTurn({ ms }).run(life, life.ticket(), setOf("request", "microphone"), "",
            controller = { _, _ -> error("no phone TTS") }, acknowledgement = "ack", progress = "working",
            model = { error("native controller owns model") }, completed = { assertEquals("actual final", it.reply.text); commits++ },
            native = { lease ->
                val wire = NativeProgressWire(lease, "request", "question", "selected-voice", pause, { bytes, _ -> events += parseNetEvent(bytes).id })
                NativeProgress({ ms }, pause).run(lease, wire, "ack", { "working" }, {
                    while (ms < 20_000) yield()
                    Reply("actual final")
                })
            })
        assertEquals(1, commits)
        assertEquals(1, events.count { it == EV_MWA_VOICE_INTERACTION_COMPLETE })
        assertEquals(3, events.count { it == EV_VOICE_ACTION_RESPONSE })
    }

    @Test fun finalCancelsInFlightPhraseAndNeverSpeaksLateUpdate() = runBlocking {
        var ms = 0L
        val events = mutableListOf<String>()
        val result = CompletableDeferred<Reply>()
        val requested = CompletableDeferred<Unit>()
        var cancelled = false
        val lease = DiagnosticLease({ ms }, { true }, 120_000)
        val pause: suspend (Long) -> Unit = { ms += it; yield(); if (requested.isCompleted) { result.complete(Reply("final")); yield() } }
        val wire = NativeProgressWire(lease, "interaction", "question", "voice", pause, { bytes, _ -> events += bytes.toString(Charsets.ISO_8859_1) })
        val reply = NativeProgress({ ms }, pause).run(lease, wire, "ack", {
            requested.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }, { result.await() })
        assertEquals("final", reply.reply.text)
        assertTrue(cancelled)
        assertTrue(events.any { it.contains("final") })
        assertTrue(ms < 16_000)
    }
}

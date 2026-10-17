package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.launch

class NativeTtsDiagnosticTest {
    @Test fun baselineUsesOrdinaryNativeFramingAndRejectsPhoneSelection() = kotlinx.coroutines.runBlocking {
        for (selected in listOf(false, true)) for (simplified in listOf(false, true)) {
            var ms = 0L
            val frames = mutableListOf<Pair<ByteArray, Int>>()
            val lease = DiagnosticLease({ ms }, { true }, 45_000)
            val ran = NativeTtsDiagnostic.run(TimeoutDiagnostic.Mode.NATIVE_BASELINE, lease, selected, simplified,
                "request", "synthetic command", "selected-voice", { ms += it },
                { bytes, type -> frames += bytes to type })
            assertEquals(selected, ran)
            if (!selected) { assertTrue(frames.isEmpty()); continue }
            val ids = frames.map { parseNetEvent(it.first).id }
            assertEquals(listOf(71, 52, 342) + (if (simplified) listOf(53, 60) else listOf(60, 53)) + 392, ids)
            assertEquals(listOf(1010, 0, 0, 1010, 1010, 1010), frames.map { it.second })
            val complete = com.macedonga.visor.core.protocol.Thrift.loads(frames.first { parseNetEvent(it.first).id == 60 }.first.let { parseNetEvent(it).body })
            assertEquals("request", complete[1].asText())
            assertEquals("Native baseline test", complete[4].asStruct()[1].asText())
            val varm = com.macedonga.visor.core.protocol.Thrift.loads(parseNetEvent(frames.first { parseNetEvent(it.first).id == 53 }.first).body)
            assertEquals(false, varm[6])
            assertEquals(false, varm[14])
            assertEquals("selected-voice", varm[13].asText())
            assertTrue(ms < 1_000)
        }
    }

    @Test fun interimOmitsCompletionUntilFixedFinalAtThirtyFiveSeconds() = kotlinx.coroutines.runBlocking {
        for (simplified in listOf(false, true)) {
            var ms = 0L
            val frames = mutableListOf<Pair<Long, NetEvent>>()
            NativeTtsDiagnostic.run(TimeoutDiagnostic.Mode.NATIVE_INTERIM, DiagnosticLease({ ms }, { true }, 45_000), true, simplified,
                "request", "synthetic", "selected-voice", { ms += it }, { bytes, _ -> frames += ms to parseNetEvent(bytes) })
            val nonKeepalive = frames.filter { it.second.id != 392 }
            assertEquals(listOf(71, 52, 342, 53) + (if (simplified) listOf(53, 60) else listOf(60, 53)), nonKeepalive.map { it.second.id })
            assertTrue(nonKeepalive.take(4).all { it.first < 1_000 })
            assertTrue(nonKeepalive.drop(4).all { it.first in 35_000L..35_100L })
            val complete = com.macedonga.visor.core.protocol.Thrift.loads(nonKeepalive.last { it.second.id == 60 }.second.body)
            assertEquals("Native test completed", complete[4].asStruct()[1].asText())
            val keepalives = frames.filter { it.second.id == 392 }.map { it.first to com.macedonga.visor.core.protocol.Thrift.loads(it.second.body) }
            assertEquals((3_000L..33_000L step 3_000L).toList(), keepalives.filter { it.second[1].asText() == "AwaitingResponse" }.map { it.first })
            assertEquals((1L..11L).toList(), keepalives.take(11).map { it.second[3] })
            assertTrue(keepalives.take(11).all { it.second[2] == 20_000L })
            assertEquals("VoiceActionResponseMessage", keepalives.last().second[1].asText())
        }
    }

    @Test fun completionProbeAddsTtsMessageButKeepsInterimWaitPolicy() = kotlinx.coroutines.runBlocking {
        for (simplified in listOf(false, true)) {
            var ms = 0L
            val frames = mutableListOf<Pair<Long, NetEvent>>()
            NativeTtsDiagnostic.run(TimeoutDiagnostic.Mode.NATIVE_COMPLETION, DiagnosticLease({ ms }, { true }, 45_000), true, simplified,
                "request", "synthetic", "selected-voice", { ms += it }, { bytes, _ -> frames += ms to parseNetEvent(bytes) })
            val speech = frames.filter { it.second.id != 392 }
            assertEquals(listOf(71, 52, 342) + (if (simplified) listOf(53, 60, 53, 60) else listOf(60, 53, 60, 53)), speech.map { it.second.id })
            val completions = speech.filter { it.second.id == 60 }
            assertTrue(completions.first().first < 1_000)
            assertTrue(completions.last().first in 35_000L..35_100L)
            assertEquals(listOf("Native test started", "Native test completed"), completions.map {
                com.macedonga.visor.core.protocol.Thrift.loads(it.second.body)[4].asStruct()[1].asText()
            })
            assertEquals((3_000L..33_000L step 3_000L).toList(), frames.filter {
                it.second.id == 392 && com.macedonga.visor.core.protocol.Thrift.loads(it.second.body)[1].asText() == "AwaitingResponse"
            }.map { it.first })
        }
    }

    @Test fun diagnosticBudgetRejectsSleepJumpBeforeAnyLateFinal() = kotlinx.coroutines.runBlocking {
        var ms = 0L
        val events = mutableListOf<Int>()
        try {
            NativeTtsDiagnostic.run(TimeoutDiagnostic.Mode.NATIVE_INTERIM, DiagnosticLease({ ms }, { true }), true, true,
                "request", "synthetic", null, { ms += if (it > 100) 46_000 else it }, { bytes, _ -> events += parseNetEvent(bytes).id })
            fail("45 second diagnostic budget")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(listOf(71, 52, 342, 53), events)
    }

    @Test fun nativeSelectionRequiresEnabledGlassesVoiceOutsideLiveSessions() {
        val config = AssistantConfig()
        assertTrue(NativeTtsDiagnostic.allowed(config, false, false))
        assertFalse(NativeTtsDiagnostic.allowed(config.copy(enabled = false), false, false))
        assertFalse(NativeTtsDiagnostic.allowed(config.copy(glassesVoice = false), false, false))
        assertFalse(NativeTtsDiagnostic.allowed(config, true, false))
        assertFalse(NativeTtsDiagnostic.allowed(config, false, true))
        assertFalse(TimeoutDiagnostic.Mode.DELAYED.nativeProbe)
        assertFalse(TimeoutDiagnostic.Mode.STREAMING.nativeProbe)
        for (mode in listOf(TimeoutDiagnostic.Mode.NATIVE_BASELINE, TimeoutDiagnostic.Mode.NATIVE_INTERIM, TimeoutDiagnostic.Mode.NATIVE_COMPLETION)) assertTrue(mode.nativeProbe)
    }

    @Test fun remoteCloseLogsOnlyNumericCodeForOwnedAlias() {
        val lifetime = DiagnosticLifetime()
        val notes = mutableListOf<String>()
        lifetime.begin(lifetime.ticket(), setOf("private-request", "private-mic"), { 0L }, { notes += it })!!
        assertFalse(lifetime.remoteComplete("unrelated", 1))
        assertTrue(lifetime.remoteComplete("private-mic", 95))
        assertEquals(listOf("remote-close-code-95", "remote-complete"), notes)
    }

    @Test fun ownedProbeInvalidatesEveryAliasSettingsAndCoroutineBeforeLateWrites() = kotlinx.coroutines.runBlocking {
        for (reason in listOf("new-microphone", "new-request", "new-followup", "settings-changed", "channel-closed", "link-replaced", "request", "mic", "ownership", "cancel")) {
            var ms = 0L
            val life = DiagnosticLifetime()
            var allowed = true
            var writes = 0
            var atRevoke = -1
            var finished = 0
            val job = launch {
                NativeTtsDiagnostic.runOwned(life, life.ticket(), setOf("request", "mic"), { ms }, { allowed },
                    TimeoutDiagnostic.Mode.NATIVE_COMPLETION, true, "request", "synthetic", "voice",
                    pause = {
                        ms += it
                        if (ms >= 3_000 && atRevoke == -1) {
                            atRevoke = writes
                            when (reason) {
                                "request", "mic" -> life.remoteComplete(reason, 95)
                                "ownership" -> allowed = false
                                "cancel" -> kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                                else -> life.invalidate(reason)
                            }
                        }
                    }, send = { _, _ -> writes++ }, completed = { finished++ })
            }
            job.join()
            assertTrue(reason, job.isCancelled)
            assertEquals(reason, atRevoke, writes)
            assertEquals(reason, 0, finished)
        }
    }

    @Test fun changingNativeVoiceDuringWaitRevokesEveryLaterWrite() = kotlinx.coroutines.runBlocking {
        for (mode in listOf(TimeoutDiagnostic.Mode.NATIVE_INTERIM, TimeoutDiagnostic.Mode.NATIVE_COMPLETION)) {
            for (simplified in listOf(false, true)) for (replacement in listOf("other-voice", null)) {
                val config = AssistantConfig()
                var currentVoice: String? = "selected-voice"
                val selectedVoice = currentVoice
                var ms = 0L
                var writes = 0
                var writesAtChange = -1
                var finished = 0
                val life = DiagnosticLifetime()
                val job = launch {
                    NativeTtsDiagnostic.runOwned(life, life.ticket(), setOf("request", "mic"), { ms },
                        { NativeTtsDiagnostic.allowed(config, false, false, selectedVoice, currentVoice) },
                        mode, simplified, "request", "synthetic", selectedVoice,
                        pause = {
                            ms += it
                            if (ms >= 15_000 && writesAtChange == -1) {
                                writesAtChange = writes
                                currentVoice = replacement
                            }
                        }, send = { _, _ -> writes++ }, completed = { finished++ })
                }
                job.join()
                assertTrue("probe reached voice change during 35 second wait", writesAtChange > 0)
                assertEquals("voice change must stop keepalives and late final", writesAtChange, writes)
                assertTrue("ownership loss cancels probe", job.isCancelled)
                assertEquals("revoked probe cannot publish completion", 0, finished)
            }
        }
    }

    @Test fun ownedProbeRejectsStaleTicketAndDisabledSelectionWithoutWrites() = kotlinx.coroutines.runBlocking {
        val life = DiagnosticLifetime()
        val ticket = life.ticket()
        life.invalidate("settings-changed")
        for (selected in listOf(false, true)) {
            NativeTtsDiagnostic.runOwned(life, ticket, setOf("request"), { 0L }, { selected },
                TimeoutDiagnostic.Mode.NATIVE_BASELINE, true, "request", "synthetic", null,
                {}, { _, _ -> fail("no writes") }, completed = { fail("no publication") })
        }
    }

    @Test fun ownedProbeCompletesOnceWithoutModelOrRetryHistoryDependencies() = kotlinx.coroutines.runBlocking {
        val life = DiagnosticLifetime()
        var ms = 0L
        var writes = 0
        var finished = 0
        repeat(2) {
            NativeTtsDiagnostic.runOwned(life, life.ticket(), setOf("request"), { ms }, { true },
                TimeoutDiagnostic.Mode.NATIVE_INTERIM, true, "request", "synthetic", null,
                { ms += it }, { _, _ -> writes++ }, completed = { finished++ })
        }
        assertEquals(1, finished)
        assertEquals(18, writes) // 3 preamble, interim VARM, 11 keepalives, final pair, final keepalive.
        assertTrue(ms in 35_000L..35_100L)
    }

    @Test fun nativeRunnerRejectsLegacyModesBeforeAnyWireWrite() = kotlinx.coroutines.runBlocking {
        for (mode in listOf(TimeoutDiagnostic.Mode.DELAYED, TimeoutDiagnostic.Mode.STREAMING)) {
            try {
                NativeTtsDiagnostic.run(mode, DiagnosticLease({ 0L }, { true }), true, true, "request", "synthetic", null,
                    {}, { _, _ -> fail("legacy mode must not reach wire") })
                fail("legacy mode")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun failuresLogOnlyMonotonicMetadataNeverTransportMessageOrTranscript() = kotlinx.coroutines.runBlocking {
        var ms = 0L
        val notes = mutableListOf<String>()
        try {
            NativeTtsDiagnostic.runOwned(DiagnosticLifetime(), 0L, setOf("private-id"), { ms }, { true },
                TimeoutDiagnostic.Mode.NATIVE_BASELINE, true, "private-id", "private-transcript", "private-voice",
                { ms += it }, { _, _ -> throw IllegalStateException("private transport error") }, { notes += it })
            fail("transport failure")
        } catch (_: IllegalStateException) { }
        assertTrue(notes.contains("elapsedMs=0 event=failed-IllegalStateException"))
        assertFalse(notes.joinToString().contains("private"))
    }

    @Test fun exactNativePhrasesOptInWithoutChangingOrdinaryRouting() {
        val phrases = mapOf(
            "test native baseline" to "NATIVE_BASELINE",
            "test native interim" to "NATIVE_INTERIM",
            "test native completion" to "NATIVE_COMPLETION",
        )
        for ((phrase, mode) in phrases) {
            assertEquals(mode, TimeoutDiagnostic.recognize("Hey Meta, $phrase!")?.name)
            // Recognition must reserve the command even with phone voice selected: never call a model.
            for (native in listOf(false, true)) {
                val config = AssistantConfig(glassesVoice = native)
                assertEquals(AnswerRoute.DIAGNOSTIC, AssistantTurnRouter.routeAnswer(phrase, config, false))
                assertEquals(FollowupAction.SESSION_ALIAS, AssistantTurnRouter.routeFollowup(phrase, config, false))
            }
            assertNull(TimeoutDiagnostic.recognize("please $phrase"))
            assertNull(TimeoutDiagnostic.recognize("$phrase now"))
        }
        assertEquals(AnswerRoute.ORDINARY, AssistantTurnRouter.routeAnswer("hello", AssistantConfig(), false))
    }
}

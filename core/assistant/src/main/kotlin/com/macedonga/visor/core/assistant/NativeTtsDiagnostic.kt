package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.protocol.ThriftWriter

/** Deterministic protocol probes, not a native progress fix. */
internal object NativeTtsDiagnostic {
    const val BUDGET_MS = 45_000L
    fun allowed(
        config: AssistantConfig, sessionTurn: Boolean, liveAiActive: Boolean,
        selectedVoice: String? = null, currentVoice: String? = null,
    ): Boolean =
        config.enabled && config.glassesVoice && !sessionTurn && !liveAiActive && selectedVoice == currentVoice

    suspend fun runOwned(
        lifetime: DiagnosticLifetime, ticket: Long, aliases: Set<String>, now: () -> Long, allowed: () -> Boolean,
        mode: TimeoutDiagnostic.Mode, simplified: Boolean, interaction: String, transcript: String, voiceId: String?,
        pause: suspend (Long) -> Unit, send: suspend (ByteArray, Int) -> Unit,
        note: (String) -> Unit = {}, completed: () -> Unit = {},
    ) {
        if (!allowed()) { note("elapsedMs=0 event=rejected-native-selection-required"); return }
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        var lease: DiagnosticLease? = null
        fun trace(event: String) = note("elapsedMs=${lease?.elapsedMs ?: 0} event=$event")
        lease = lifetime.begin(ticket, aliases, now, ::trace, { job?.cancel() }, BUDGET_MS, allowed) ?: return
        val active = lease
        try {
            kotlinx.coroutines.withTimeout(BUDGET_MS) {
                trace("start-simplified-$simplified")
                run(mode, active, true, simplified, interaction, transcript, voiceId, pause, send, ::trace)
                active.check()
                lifetime.publish(ticket, completed)
                trace("finished")
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            trace("aborted")
            active.revoke()
            throw cancelled
        } catch (error: Exception) {
            trace("failed-${error.javaClass.simpleName}")
            throw error
        } finally {
            active.revoke()
        }
    }

    suspend fun run(
        mode: TimeoutDiagnostic.Mode, lease: DiagnosticLease, nativeSelected: Boolean, simplified: Boolean,
        interaction: String, transcript: String, voiceId: String?, pause: suspend (Long) -> Unit,
        send: suspend (ByteArray, Int) -> Unit,
        note: (String) -> Unit = {},
    ): Boolean {
        require(mode.nativeProbe) { "not a native diagnostic mode" }
        if (!nativeSelected) { note("rejected-native-selection-required"); return false }
        suspend fun checkBudget() {
            lease.check()
            if (lease.elapsedMs >= BUDGET_MS) throw kotlinx.coroutines.CancellationException("native diagnostic budget")
        }
        suspend fun emit(bytes: ByteArray, type: Int) {
            checkBudget()
            lease.send { send(bytes, type) }
            note("wire-${parseNetEvent(bytes).id}-type-$type")
        }
        suspend fun event(id: Int, body: ByteArray, type: Int = MT_NET_EVENT) = emit(netEvent(id, body), type)
        event(EV_START_OF_USER_SPEECH, ThriftWriter().str(1, interaction).done())
        pause(20)
        event(EV_FINAL_TRANSCRIPTION, ThriftWriter().str(1, transcript).i32(2, 0).str(4, "").bool(7, false).str(8, interaction).done(), 0)
        pause(20)
        event(EV_ON_PHONE_UNDERSTANDING, ThriftWriter().str(1, interaction).bool(2, false).str(3, REPLY_INTENT).bool(4, false).done(), 0)
        pause(20)
        val ordinary = AnswerWire(simplified, pause) { _, bytes, type -> emit(bytes, checkNotNull(type)) }
        if (mode == TimeoutDiagnostic.Mode.NATIVE_BASELINE) {
            ordinary.native(0, interaction, transcript, "Native baseline test", voiceId)
        } else {
            if (mode == TimeoutDiagnostic.Mode.NATIVE_COMPLETION) {
                ordinary.native(0, interaction, transcript, "Native test started", voiceId)
            } else {
                event(EV_VOICE_ACTION_RESPONSE, varm(interaction, transcript, "Native test started", onGlasses = true, voiceId = voiceId))
            }
            var next = 3_000L
            while (lease.elapsedMs < 35_000L) {
                lease.check()
                val target = minOf(next, 35_000L)
                val left = target - lease.elapsedMs
                if (left > 0) pause(left)
                lease.check()
                if (target < 35_000L) event(EV_KEEP_AIR_SESSION_ALIVE, ThriftWriter().str(1, "AwaitingResponse")
                    .i32(2, 20_000).i32(3, (next / 3_000).toInt()).done())
                next += 3_000
            }
            ordinary.native(0, interaction, transcript, "Native test completed", voiceId)
        }
        event(EV_KEEP_AIR_SESSION_ALIVE, ThriftWriter().str(1, "VoiceActionResponseMessage")
            .i32(2, (lease.elapsedMs and 0xFFFF).toInt()).i32(3, 1).done())
        return true
    }
}

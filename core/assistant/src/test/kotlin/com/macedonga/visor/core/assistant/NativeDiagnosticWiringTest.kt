package com.macedonga.visor.core.assistant

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Android service wiring cannot run on host JVM; controller behavior tested separately. */
class NativeDiagnosticWiringTest {
    private val source = File("src/main/kotlin/com/macedonga/visor/core/assistant/Assistant.kt").readText()

    @Test fun nativeProbeBranchesBeforeLegacySynthesisWithoutHistoryOrModelCalls() {
        val start = source.indexOf("if (mode.nativeProbe)", source.indexOf("private suspend fun runDiagnostic"))
        assertTrue("dedicated native probe entry", start >= 0)
        val end = source.indexOf("val job =", start)
        val branch = source.substring(start, end)
        assertTrue(branch.contains("NativeTtsDiagnostic.runOwned("))
        assertTrue(branch.contains("NativeTtsDiagnostic.allowed("))
        assertTrue(branch.contains("return"))
        for (forbidden in listOf("brain.", "voice.", "record(", "TurnRecord(", "update(interaction)")) assertFalse(forbidden, branch.contains(forbidden))
        assertTrue(branch.contains("store.config.value == config"))
    }

    @Test fun nativeProbeUsesCapturedVoiceForWireAndLiveOwnershipChecks() {
        val start = source.indexOf("if (mode.nativeProbe)", source.indexOf("private suspend fun runDiagnostic"))
        val branch = source.substring(start, source.indexOf("val job =", start))
        assertTrue("capture voice once before controller starts", branch.contains("val selectedVoice = glassesVoice()"))
        assertTrue("lease predicate must reread current voice", branch.contains(
            "NativeTtsDiagnostic.allowed(store.config.value, l.sessionTurn, liveAi.active, selectedVoice, glassesVoice())"))
        assertTrue("wire uses same captured voice", branch.contains(
            "mode, l.simplified, interaction, transcript, selectedVoice,"))
    }

    @Test fun settingsInvalidateDiagnosticQueuedTickets() {
        val collector = source.substring(source.indexOf("store.config.collect"), source.indexOf("// A language switch"))
        assertTrue(collector.contains("diagnostic?.invalidate(\"diagnostic-settings-changed\")"))
    }

    @Test fun remoteDiagnosticCloseDoesNotLogRawPayload() {
        val branch = source.substring(source.indexOf("EV_REMOTE_INTERACTION_COMPLETE ->"), source.indexOf("EV_FRAME_KEEP_ALIVE_RESPONSE, EV_KEEP_MWA_ALIVE"))
        assertTrue(branch.contains("val diagnosticClosed"))
        assertTrue(branch.contains("if (!diagnosticClosed) say("))
        assertTrue(branch.contains("fields[2] as? Long"))
    }
}

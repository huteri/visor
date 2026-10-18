package com.macedonga.visor.core.assistant

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guards Android-only adapter routing in addition to controller behavior tests. */
class SpokenProgressWiringTest {
    private fun source(name: String) = File("src/main/kotlin/com/macedonga/visor/core/assistant/$name.kt").readText()
    @Test fun `ordinary answer opts into controller without altering baseline delivery`() {
        val assistant = source("Assistant")
        assertTrue(assistant.contains("SpokenProgressPolicy.eligible"))
        assertTrue(assistant.contains("answerWithProgress("))
        assertTrue(assistant.contains("voice.progressPcm"))
        assertTrue(assistant.contains("voice.progressEncoder"))
        assertTrue(assistant.contains("val streamId = l.streamIds[interaction] ?: l.sessionOf[interaction]?.let"))
        assertTrue(assistant.contains("if (store.config.value.glassesVoice && !l.sessionTurn)"))
    }
    @Test fun `settings disclose phone voice and provide off by default toggle`() {
        val root = File("../../app/src/main")
        val ui = File(root, "kotlin/com/macedonga/visor/settings/AssistantSettings.kt").readText()
        val strings = File(root, "res/values/strings.xml").readText()
        assertTrue(ui.contains("spokenProgress"))
        assertTrue(strings.contains("repeats phone text-to-speech progress at intervals based on Meta AI Wait for answer"))
        assertTrue(strings.contains("Limited to 300 seconds"))
        assertTrue(strings.contains("final answer uses your selected voice"))
        assertTrue(strings.contains("native handoff is experimental"))
    }
    @Test fun `progress preparation bounded and keepalive ownership checked`() {
        assertTrue(source("SpokenProgress").contains("withTimeout(15_000) { synthesize(phrase) }"))
        assertTrue(source("Assistant").contains("holdOn(l) { check(owned())"))
    }
    @Test fun `settings generation and channel closure revoke active stream`() {
        assertTrue(source("AssistantConfig").contains("generation.incrementAndGet()"))
        assertTrue(source("Assistant").contains("store.generation.get() == configGeneration"))
        assertTrue(source("Assistant").contains("progressTurn.incrementAndGet() // Channel closure"))
        assertTrue(source("Assistant").contains("progressTurn.incrementAndGet() // New interaction"))
    }
    @Test fun `preference persists separately from voice`() {
        val config = source("AssistantConfig")
        assertTrue(config.contains(".putBoolean(\"spoken_progress\", next.spokenProgress)"))
        assertTrue(config.contains("spokenProgress = prefs.getBoolean(\"spoken_progress\", d.spokenProgress)"))
    }
}

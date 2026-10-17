package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test

class ProgressPolicyTest {
    @Test fun `progress is explicit off by default Hermes only and leaves native voice preference intact`() {
        val defaults = AssistantConfig()
        assertFalse(defaults.spokenProgress)
        assertTrue(defaults.glassesVoice)
        assertFalse(defaults.usesSpokenProgress())
        val selected = defaults.copy(spokenProgress = true, provider = Provider.OPENAI_COMPATIBLE)
        assertTrue(selected.usesSpokenProgress())
        assertTrue(selected.glassesVoice)
        assertFalse(selected.copy(provider = Provider.CLAUDE).usesSpokenProgress())
        assertFalse(selected.copy(enabled = false).usesSpokenProgress())
        assertFalse(selected.usesSpokenProgress(live = true))
    }
}

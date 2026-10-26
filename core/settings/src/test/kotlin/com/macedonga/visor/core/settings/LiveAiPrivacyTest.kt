package com.macedonga.visor.core.settings

import org.junit.Assert.*
import org.junit.Test

class LiveAiPrivacyTest {
    @Test fun currentCompleteExplicitSettingRequired() {
        val fresh = SettingsState(stored = mapOf("live_ai_voice_only" to "false"), readAt = 1, connectionGeneration = 7, complete = true)
        assertEquals(false, fresh.liveAiVoiceOnly(7, true))
        assertEquals(true, fresh.copy(stored = mapOf("live_ai_voice_only" to "true")).liveAiVoiceOnly(7, true))
        assertNull(fresh.liveAiVoiceOnly(8, true))
        assertNull(fresh.liveAiVoiceOnly(7, false))
        assertNull(fresh.copy(complete = false).liveAiVoiceOnly(7, true))
        assertNull(fresh.copy(stored = emptyMap()).liveAiVoiceOnly(7, true))
        assertNull(fresh.copy(stored = mapOf("live_ai_voice_only" to "invalid")).liveAiVoiceOnly(7, true))
        // A same-generation delta refresh retains the last completed explicit consent.
        assertEquals(false, fresh.copy(reading = true).liveAiVoiceOnly(7, true))
        assertNull(fresh.copy(reading = true, complete = false).liveAiVoiceOnly(7, true))
        assertNull(fresh.copy(reading = true).liveAiVoiceOnly(8, true))
        assertNull(fresh.copy(reading = true, pending = mapOf("live_ai_voice_only" to "true")).liveAiVoiceOnly(7, true))
        assertNull(fresh.copy(pending = mapOf("live_ai_voice_only" to "true")).liveAiVoiceOnly(7, true))
        assertNull(fresh.copy(problem = "timeout").liveAiVoiceOnly(7, true))
    }
}

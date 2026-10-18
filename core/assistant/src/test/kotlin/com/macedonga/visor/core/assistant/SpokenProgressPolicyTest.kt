package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test

class SpokenProgressPolicyTest {
    @Test fun `network read allowance extended only in explicit progress context`() = kotlinx.coroutines.runBlocking {
        assertEquals(45_000, progressReadTimeout(kotlin.coroutines.coroutineContext))
        kotlinx.coroutines.withContext(ProgressNetworkBudget()) {
            assertEquals(300_000, progressReadTimeout(kotlin.coroutines.coroutineContext))
        }
    }
    @Test fun `opt in leaves saved voice unchanged and excludes active modes`() {
        val initial = AssistantConfig()
        assertFalse(initial.spokenProgress)
        val enabled = initial.copy(spokenProgress = true)
        assertEquals(initial.glassesVoice, enabled.glassesVoice)
        assertTrue(SpokenProgressPolicy.eligible(enabled, live = false, session = false))
        assertFalse(SpokenProgressPolicy.eligible(enabled, live = true, session = false))
        assertFalse(SpokenProgressPolicy.eligible(enabled, live = false, session = true))
        assertFalse(SpokenProgressPolicy.eligible(initial, live = false, session = false))
    }
}

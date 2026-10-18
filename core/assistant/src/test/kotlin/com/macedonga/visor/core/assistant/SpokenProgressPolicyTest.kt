package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test

class SpokenProgressPolicyTest {
    @Test fun `repeat cadence reuses effective first update with positive short wait floor`() {
        for ((wait, interval) in listOf(55L to 40_000L, 30L to 20_000L, 20L to 10_000L)) {
            assertEquals(interval, SpokenProgressPolicy.startMs(wait))
            assertEquals(interval, SpokenProgressPolicy.repeatMs(wait))
        }
        for (wait in listOf(0L, 9L, 10L)) {
            assertEquals(0L, SpokenProgressPolicy.startMs(wait))
            assertEquals(1_000L, SpokenProgressPolicy.repeatMs(wait))
        }
        assertEquals(1_000L, SpokenProgressPolicy.repeatMs(11))
    }

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

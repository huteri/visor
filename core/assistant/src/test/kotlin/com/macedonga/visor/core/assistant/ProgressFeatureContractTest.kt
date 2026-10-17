package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test

class ProgressFeatureContractTest {
    @Test fun newPreferencesAreExplicitOffByDefaultAndDoNotChangeOrdinaryVoice() {
        val defaults = AssistantConfig()
        assertFalse(defaults.generatedProgress)
        assertFalse(defaults.nativeProgress)
        assertFalse(defaults.spokenProgress)
        assertTrue(defaults.glassesVoice)
        val opted = defaults.copy(provider = Provider.OPENAI_COMPATIBLE, nativeProgress = true, generatedProgress = true)
        assertEquals(AnswerRoute.ORDINARY, AssistantTurnRouter.routeAnswer("question", opted, false))
        assertTrue(opted.glassesVoice)
        val phone = opted.copy(spokenProgress = true, nativeProgress = false)
        assertEquals(AnswerRoute.PROGRESS, AssistantTurnRouter.routeAnswer("question", phone, false))
        assertTrue(phone.glassesVoice)
    }
}

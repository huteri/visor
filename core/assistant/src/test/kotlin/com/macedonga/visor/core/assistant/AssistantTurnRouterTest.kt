package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantTurnRouterTest {
    private val progressConfig = AssistantConfig(
        enabled = true,
        spokenProgress = true,
        provider = Provider.OPENAI_COMPATIBLE,
    )

    @Test fun `answer routing falls through to ordinary path when Live AI is active even if spoken progress is enabled`() {
        val route = AssistantTurnRouter.routeAnswer("what am I looking at?", progressConfig, liveAiActive = true)
        assertEquals(AnswerRoute.ORDINARY, route)
    }

    @Test fun `followup routing sends preamble when Live AI is active even if spoken progress is enabled`() {
        val action = AssistantTurnRouter.routeFollowup("and now?", progressConfig, liveAiActive = true)
        assertEquals(FollowupAction.PREAMBLE, action)
    }

    @Test fun `utterance does not drop on stale progress ticket when Live AI is active`() {
        val drop = AssistantTurnRouter.shouldDropProgressUtterance(progressConfig, liveAiActive = true, progressTicket = 1L, currentTicket = 2L)
        assertFalse(drop)
    }

    @Test fun `spoken progress routes when Live AI is inactive`() {
        val route = AssistantTurnRouter.routeAnswer("tell me a story", progressConfig, liveAiActive = false)
        assertEquals(AnswerRoute.PROGRESS, route)

        val action = AssistantTurnRouter.routeFollowup("what next?", progressConfig, liveAiActive = false)
        assertEquals(FollowupAction.SESSION_ALIAS, action)

        val drop = AssistantTurnRouter.shouldDropProgressUtterance(progressConfig, liveAiActive = false, progressTicket = 1L, currentTicket = 2L)
        assertTrue(drop)
    }

    @Test fun `timeout diagnostic routes to diagnostic regardless of live ai or spoken progress state`() {
        val diagnosticPhrase = "test delayed answer"
        assertEquals(AnswerRoute.DIAGNOSTIC, AssistantTurnRouter.routeAnswer(diagnosticPhrase, progressConfig, liveAiActive = true))
        assertEquals(AnswerRoute.DIAGNOSTIC, AssistantTurnRouter.routeAnswer(diagnosticPhrase, progressConfig, liveAiActive = false))
    }
}

package com.macedonga.visor.core.assistant

internal enum class AnswerRoute {
    DIAGNOSTIC,
    PROGRESS,
    ORDINARY
}

internal enum class FollowupAction {
    PREAMBLE,
    SESSION_ALIAS
}

/** Pure routing seam for incoming questions and follow-ups across Live AI, diagnostics, and spoken progress. */
internal object AssistantTurnRouter {
    fun routeAnswer(transcript: String, config: AssistantConfig, liveAiActive: Boolean): AnswerRoute {
        if (TimeoutDiagnostic.recognize(transcript) != null) return AnswerRoute.DIAGNOSTIC
        if (config.usesSpokenProgress(liveAiActive)) return AnswerRoute.PROGRESS
        return AnswerRoute.ORDINARY
    }

    fun routeFollowup(text: String, config: AssistantConfig, liveAiActive: Boolean): FollowupAction {
        return if (TimeoutDiagnostic.recognize(text) == null && !config.usesSpokenProgress(liveAiActive)) {
            FollowupAction.PREAMBLE
        } else {
            FollowupAction.SESSION_ALIAS
        }
    }

    fun shouldDropProgressUtterance(config: AssistantConfig, liveAiActive: Boolean, progressTicket: Long, currentTicket: Long): Boolean {
        return config.usesSpokenProgress(liveAiActive) && currentTicket != progressTicket
    }
}

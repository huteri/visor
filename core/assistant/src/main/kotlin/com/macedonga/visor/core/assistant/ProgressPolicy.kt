package com.macedonga.visor.core.assistant

internal fun AssistantConfig.usesSpokenProgress(live: Boolean = false): Boolean = enabled && spokenProgress && provider == Provider.OPENAI_COMPATIBLE && !live

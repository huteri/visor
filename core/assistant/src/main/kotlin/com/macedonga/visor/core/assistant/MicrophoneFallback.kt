package com.macedonga.visor.core.assistant

/** Only an exact microphone ID or an already observed ev57 session alias proves ownership.
 * An ev65 UUID by itself does not establish that it owns whichever microphone happens to be open.
 */
internal class MicrophoneFallback {
    private var current: String? = null
    private var generation = 0L
    private val aliases = mutableSetOf<String>()

    @Synchronized fun listen(interaction: String): Long {
        aliases.clear()
        current = interaction
        return ++generation
    }

    @Synchronized fun allowed(ticket: Long): Boolean = ticket == generation

    /** Called only where ev57 establishes sessionOf for this microphone generation. */
    @Synchronized fun associate(request: String, session: String) {
        if (session == current) aliases.add(request)
    }

    @Synchronized fun suppress(request: String): Boolean {
        if (current == null || (request != current && request !in aliases)) return false
        aliases.clear()
        generation++
        return true
    }
}

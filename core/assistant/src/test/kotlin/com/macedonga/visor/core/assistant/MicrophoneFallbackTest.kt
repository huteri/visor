package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test

class MicrophoneFallbackTest {
    @Test fun `an old session alias cannot suppress a newer microphone generation`() {
        val guard = MicrophoneFallback()
        guard.listen("session")
        guard.associate("old-request", "session")
        assertTrue(guard.suppress("old-request"))
        val next = guard.listen("session")
        assertFalse(guard.suppress("old-request"))
        assertTrue(guard.allowed(next))
    }

    @Test fun `a verified distinct request session alias invalidates queued recognition`() {
        val guard = MicrophoneFallback()
        val queued = guard.listen("session")
        guard.associate("request", "session")
        assertTrue(guard.suppress("request"))
        assertFalse(guard.allowed(queued))
    }

    @Test fun `unrelated or unassociated requests leave current listening alone`() {
        val guard = MicrophoneFallback()
        val queued = guard.listen("current")
        guard.associate("other", "old-session")
        assertFalse(guard.suppress("other"))
        assertFalse(guard.suppress("unknown"))
        assertTrue(guard.allowed(queued))
    }

    @Test fun `same id suppression invalidates queued callbacks but not a new microphone turn`() {
        val guard = MicrophoneFallback()
        val old = guard.listen("same")
        assertTrue(guard.suppress("same"))
        assertFalse(guard.allowed(old))
        val next = guard.listen("same")
        assertTrue(guard.allowed(next))
        assertFalse(guard.allowed(old))
    }
}

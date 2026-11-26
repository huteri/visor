package com.macedonga.visor.core.assistant

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class LiveAiCameraLifecycleTest {
    private class Host : LiveAiHost {
        var starts = 0
        var stops = 0
        var answers = 0
        var endedSession = ""
        override suspend fun stopSession(session: String) { endedSession = session }
        override suspend fun event(id: Int, body: ByteArray) = Unit
        override suspend fun answer(subInteraction: String, question: String) { answers++ }
        override fun cameraOn() { starts++ }
        override fun cameraOff() { stops++ }
        override fun note(text: String) = Unit
    }

    @Test fun modeFirstRequestsCameraWithoutSettingsAndDoesNotRestartOnStart() = runBlocking {
        mockConstruction(GlassesEars::class.java).use {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val host = Host()
            val live = LiveAi(mock(Context::class.java), scope, host)
            try {
                live.onModeLiveAi("session", emptyMap())
                assertEquals(1, host.starts)
                live.onModeLiveAi("session", emptyMap())
                live.onStartInteraction(mapOf(1 to "turn", 5 to 3), Locale.US)
                assertEquals(1, host.starts)
                live.end("test")
                assertEquals(1, host.stops)
            } finally { scope.cancel() }
        }
    }

    @Test fun stopUtteranceRevokesLocalSessionBeforeDeviceStop() = runBlocking {
        mockConstruction(GlassesEars::class.java).use { ears ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val host = Host()
            val live = LiveAi(mock(Context::class.java), scope, host)
            try {
                live.onModeLiveAi("session", emptyMap())
                live.onStartInteraction(mapOf(1 to "turn", 5 to 3), Locale.US)
                live.onMic(byteArrayOf(1), 5003, 14, true)
                val token = live.turnGeneration
                val heard = LiveAi::class.java.getDeclaredMethod("heard", String::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
                heard.invoke(live, "Stop Live AI", 1L, 2L)
                kotlinx.coroutines.delay(40)
                assertFalse(live.active)
                assertFalse(live.turnIsCurrent(token))
                assertEquals("session", host.endedSession)
                assertEquals(1, host.stops)
                assertEquals(0, host.answers)
                verify(ears.constructed().single()).stop()
            } finally { scope.cancel() }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun stopDuringReplacementStartupCannotInstallRecognizerOrKeepalive() = kotlinx.coroutines.test.runTest {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var block = false
        val events = mutableListOf<Int>()
        val host = object : LiveAiHost {
            override suspend fun event(id: Int, body: ByteArray) {
                events.add(id)
                if (block && id == EV_MWA_INTERACTION_CREATION_SUCCESS) release.await()
            }
            override suspend fun answer(subInteraction: String, question: String) = Unit
            override fun cameraOn() = Unit
            override fun cameraOff() = Unit
            override fun note(text: String) = Unit
        }
        mockConstruction(GlassesEars::class.java).use { ears ->
            val live = LiveAi(mock(Context::class.java), backgroundScope, host)
            live.onStartInteraction(mapOf(1 to "old", 5 to 3), Locale.US)
            assertEquals(1, ears.constructed().size)
            block = true
            val replacement = launch { live.onStartInteraction(mapOf(1 to "replacement", 5 to 3), Locale.US) }
            runCurrent()
            val heard = LiveAi::class.java.getDeclaredMethod("heard", String::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
            heard.invoke(live, "Stop Live AI", 1L, 2L)
            assertEquals(LiveAiPhase.OFF, live.phase.value)
            val stoppedAt = events.size
            release.complete(Unit)
            replacement.join()
            advanceTimeBy(6_000)
            runCurrent()
            assertEquals(LiveAiPhase.OFF, live.phase.value)
            assertEquals("Stopped startup must not construct another recognizer", 1, ears.constructed().size)
            assertEquals("Stopped startup must not emit later acknowledgments or keepalives", stoppedAt, events.size)
            val keepalive = LiveAi::class.java.getDeclaredField("keepalive").apply { isAccessible = true }.get(live)
            assertNull("Stopped startup must not install keepalive job", keepalive)
        }
    }

    @Test fun directStartWaitsForLiveModeBeforeRequestingCamera() = runBlocking {
        mockConstruction(GlassesEars::class.java).use {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val host = Host()
            val live = LiveAi(mock(Context::class.java), scope, host)
            try {
                repeat(2) { cycle ->
                    live.onStartInteraction(mapOf(1 to "turn-$cycle", 5 to 3), Locale.US)
                    live.onStartInteraction(mapOf(1 to "turn-$cycle", 5 to 3), Locale.US)
                    assertEquals(cycle, host.starts)
                    live.onModeLiveAi("session-$cycle", emptyMap())
                    assertEquals(cycle + 1, host.starts)
                    live.end("test")
                    live.end("test")
                    assertEquals(cycle + 1, host.stops)
                }
            } finally { scope.cancel() }
        }
    }
}

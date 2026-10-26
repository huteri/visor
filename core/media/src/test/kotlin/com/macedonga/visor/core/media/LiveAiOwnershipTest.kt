package com.macedonga.visor.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class LiveAiOwnershipTest {
    @Test fun pendingStartupOwnedBeforeRunning() {
        val live = mock(LiveStream::class.java, CALLS_REAL_METHODS)
        fun set(name: String, value: Any?) { LiveStream::class.java.getDeclaredField(name).apply { isAccessible = true }.set(live, value) }
        val params = Class.forName("com.macedonga.visor.core.media.LiveStream\$Params").declaredConstructors.single { it.parameterCount == 11 }.apply { isAccessible = true }
        set("params", params.newInstance(GlassesApp.MMAI_LIVE, SessionApp.MMAI_LIVE, StreamQuality.LIVE_AI, 250000, 2, false, false, 0, false, true, false))
        set("_running", MutableStateFlow(false))
        set("wanted", true)
        assertTrue("Camera off must own and cancel pending startup", live.forLiveAi)
        val startup = kotlinx.coroutines.Job()
        set("job", startup)
        var queued: (suspend (com.macedonga.visor.core.session.DataXConnection) -> Unit)? = null
        val connection = mock(com.macedonga.visor.core.connection.GlassesConnection::class.java) { invocation ->
            if (invocation.method.name == "submit") { queued = invocation.getArgument(0); null }
            else RETURNS_DEFAULTS.answer(invocation)
        }
        set("connection", connection)
        var sent = false
        val work: suspend (com.macedonga.visor.core.session.DataXConnection) -> Unit = { sent = true }
        LiveStream::class.java.getDeclaredMethod("send", kotlin.jvm.functions.Function2::class.java).apply { isAccessible = true }.invoke(live, work)
        set("wanted", false)
        startup.cancel()
        kotlinx.coroutines.runBlocking { queued!!(mock(com.macedonga.visor.core.session.DataXConnection::class.java)) }
        assertFalse("Queued startup must not send after stop", sent)
        assertFalse(live.forLiveAi)
    }
}

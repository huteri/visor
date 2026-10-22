package com.macedonga.visor.media

import com.macedonga.visor.core.media.LiveStream
import org.junit.Test
import org.mockito.Mockito.*

class LiveViewOwnershipTest {
    @Test fun leavingLiveViewDoesNotStopLiveAiCameraOrPendingStartup() {
        val live = mock(LiveStream::class.java)
        `when`(live.forLiveAi).thenReturn(true)
        val model = model(live)
        model.stop()
        verify(live, never()).stop()
    }

    @Test fun leavingLiveViewStillStopsOrdinaryCapture() {
        val live = mock(LiveStream::class.java)
        val model = model(live)
        model.stop()
        verify(live).stop()
    }

    private fun model(live: LiveStream): LiveViewModel {
        // Bypass Android ViewModel init collectors; exercise production stop delegation.
        val model = mock(LiveViewModel::class.java, CALLS_REAL_METHODS)
        LiveViewModel::class.java.getDeclaredField("live").apply { isAccessible = true }.set(model, live)
        return model
    }
}

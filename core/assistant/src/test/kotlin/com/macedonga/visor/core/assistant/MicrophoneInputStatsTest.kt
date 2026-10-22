package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test

class MicrophoneInputStatsTest {
    @Test fun cachedAudioTypeDoesNotHideBareContinuations() {
        val stats = MicrophoneInputStats()
        stats.record(13, 5003, 8, true)
        stats.record(13, 5003, 8, false)
        assertEquals("chunks=2 bytes=26 min=13 max=13 extensions=1 bare=1 type5003=2 type0=0 channel=8 channelChanges=0", stats.snapshot())
    }

    @Test fun snapshotReportsLaterDifferentSizedInputWithoutPayloadBytes() {
        val stats = MicrophoneInputStats()
        stats.record(13, 5003, 8, true)
        stats.record(320, 0, 9, false)
        assertEquals("chunks=2 bytes=333 min=13 max=320 extensions=1 bare=1 type5003=1 type0=1 channel=9 channelChanges=1", stats.snapshot())
    }

    @Test fun emptySnapshotDoesNotClaimAudio() {
        assertEquals("chunks=0 bytes=0 min=0 max=0 extensions=0 bare=0 type5003=0 type0=0 channel=-1 channelChanges=0", MicrophoneInputStats().snapshot())
    }
}

package com.macedonga.visor.core.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class LiveAiRawCaptureTest {
    private class Host(override val rawAudioCaptureEnabled: Boolean) : LiveAiHost {
        val notes = mutableListOf<String>()
        override suspend fun event(id: Int, body: ByteArray) { error("Unexpected protocol write") }
        override suspend fun answer(subInteraction: String, question: String) { error("Unexpected answer") }
        override fun cameraOn() = Unit
        override fun cameraOff() = Unit
        override fun note(text: String) { notes.add(text) }
    }
    @Test fun realLiveAiRoutesHeaderAudioShutdownAndResetsSession() {
        var now = 0L
        val host = Host(true)
        val live = LiveAi(mock(Context::class.java), CoroutineScope(Dispatchers.Unconfined), host, { now })
        live.onModeLiveAi("session", emptyMap())
        live.onMicHeader(byteArrayOf(1), 5002, 9, true)
        val bytes = byteArrayOf(2, 3)
        live.onMic(bytes, 5003, 9, false)
        bytes[0] = 99
        now = 4000
        live.onMicShutdown(byteArrayOf(4), 5004, 9, true)
        live.end("test")
        val raw = host.notes.filter { it.startsWith("RAW AUDIO SENSITIVE") }
        assertEquals(listOf("HEADER", "FIRST", "TAIL", "SHUTDOWN", "CLOSED"), raw.map { it.substringAfter("kind=").substringBefore(' ') })
        assertTrue(raw[2].endsWith("hex=0203"))
        assertTrue(raw[3].contains("elapsedMs=4000"))
        live.onModeLiveAi("next", emptyMap())
        live.onMic(byteArrayOf(5), 5003, 9, true)
        live.end("test")
        assertEquals(2, host.notes.count { it.contains("kind=FIRST index=1 ") })
    }
    @Test fun hostDefaultCaptureOff() {
        val host = Host(false)
        val live = LiveAi(mock(Context::class.java), CoroutineScope(Dispatchers.Unconfined), host, { 0L })
        live.onModeLiveAi("session", emptyMap())
        live.onMicHeader(byteArrayOf(1), 5002, 9, true)
        live.onMic(byteArrayOf(2), 5003, 9, true)
        live.onMicShutdown(byteArrayOf(3), 5004, 9, true)
        live.end("test")
        assertFalse(host.notes.any { it.startsWith("RAW AUDIO SENSITIVE") })
    }
}

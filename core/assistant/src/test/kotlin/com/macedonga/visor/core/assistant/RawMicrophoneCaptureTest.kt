package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test

class RawMicrophoneCaptureTest {
    @Test fun longSessionBoundedCopiesTailFlushesOnceAndLateWindowStartsAtThreeSeconds() {
        var now = 0L
        val lines = mutableListOf<String>()
        val capture = RawMicrophoneCapture(true, { now }, lines::add)
        capture.header(ByteArray(10000), 5002, 9, true)
        repeat(10000) { capture.packet(ByteArray(1000), 5003, 9, false) }
        assertEquals(66, lines.size)
        now = 3000
        repeat(10000) { capture.packet(ByteArray(1000), 5003, 9, false) }
        assertEquals(74, lines.size)
        val last = byteArrayOf(0x7f)
        capture.packet(last, 5003, 9, false)
        last[0] = 0
        capture.shutdown(ByteArray(1000), 5004, 9, true)
        val snapshot = lines.toList()
        capture.close()
        capture.shutdown(ByteArray(1000), 5004, 9, true)
        capture.packet(ByteArray(1000), 5003, 9, true)
        capture.header(ByteArray(1000), 5002, 9, true)
        assertEquals(snapshot, lines)
        assertEquals(4, lines.count { it.contains("kind=TAIL") })
        assertTrue(lines.first { it.contains("kind=TAIL index=20001 ") }.endsWith("hex=7f"))
        assertTrue(lines.size <= 100)
        assertTrue(lines.filter { " hex=" in it }.all { it.substringAfter("hex=").length <= 512 })
        assertTrue(lines.sumOf { if (" hex=" in it) it.substringAfter("hex=").length / 2 else 0 } <= 16384)
        assertEquals(1, lines.count { it.contains("kind=CLOSED") })
    }

    @Test fun emptyHeaderAndShortSessionStillCloseWithBoundedMetadata() {
        val lines = mutableListOf<String>()
        val capture = RawMicrophoneCapture(true, { 10L }, lines::add)
        capture.header(byteArrayOf(), 5002, 9, true)
        capture.header(byteArrayOf(1), 5002, 9, true)
        capture.close()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("len=0"))
        assertTrue(lines[0].contains("truncated=false"))
    }

    @Test fun capturesHeaderAndFirst64ConsecutivePacketsOnlyWhenOptedIn() {
        var now = 100L
        val lines = mutableListOf<String>()
        val capture = RawMicrophoneCapture(true, { now }, lines::add)
        capture.header(ByteArray(600) { 0xab.toByte() }, 5002, 9, true)
        repeat(65) { capture.packet(byteArrayOf(it.toByte()), 5003, 9, it == 0) }
        assertEquals(66, lines.size) // two header slices plus first 64
        assertTrue(lines.all { it.startsWith("RAW AUDIO SENSITIVE local-only ") })
        assertTrue(lines[0].contains("kind=HEADER index=0 elapsedMs=0 len=600 type=5002 channel=9 hasExtensions=true"))
        assertTrue(lines[0].contains("truncated=true"))
        assertTrue(lines.last().contains("index=64 "))
        val disabled = mutableListOf<String>()
        val off = RawMicrophoneCapture(false, { now }, disabled::add)
        off.header(byteArrayOf(1), 1, 1, false)
        off.packet(byteArrayOf(1), 1, 1, false)
        off.close()
        assertTrue(disabled.isEmpty())
        assertTrue(rawMicrophoneCaptureAllowed("1.3.12-livediagnostic.3"))
        assertFalse(rawMicrophoneCaptureAllowed("1.3.12"))
        assertFalse(rawMicrophoneCaptureAllowed(null))
    }
}

package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test

class NativeDeliveryTraceTest {
    @Test fun receiptsBoundedAndIdentifiersNeverPrinted() {
        val notes = mutableListOf<String>()
        var now = 100L
        val trace = NativeDeliveryTrace(1, "private-request", "private-parent", notes::add) { now }
        now += 25
        trace.receipt(360, "private-request")
        trace.receipt(360, "private-parent")
        trace.receipt(316, "private-other")
        trace.receipt(359, "")
        repeat(100) { trace.receipt(316, "private-other") }
        assertEquals(16, notes.size)
        assertTrue(notes[0].contains("elapsedMs=25"))
        assertTrue(notes[0].contains("owner=request"))
        assertTrue(notes[1].contains("owner=parent"))
        assertTrue(notes[2].contains("owner=other"))
        assertTrue(notes[3].contains("owner=absent"))
        assertTrue(notes.none { "private-" in it })
    }
}

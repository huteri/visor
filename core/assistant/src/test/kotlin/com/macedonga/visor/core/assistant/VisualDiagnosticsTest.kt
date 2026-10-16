package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class VisualDiagnosticsTest {
    @Test fun `only exact unsupported request types are retained with bounded storage`() {
        val capture = VisualDiagnostics()
        capture.start()
        capture.record(53, byteArrayOf(9)) // Not an image capture facility.
        assertNull(capture.export())
        capture.record(65, ByteArray(512 * 1024 + 1))
        val rejected = DataInputStream(ByteArrayInputStream(checkNotNull(capture.export())))
        rejected.readUTF()
        assertEquals(0, rejected.readInt())
        assertEquals(1, rejected.readInt())
        repeat(100) { capture.record(1002, ByteArray(64 * 1024), channel = 123) }
        val exported = checkNotNull(capture.export())
        assertTrue(exported.size <= 2 * 1024 * 1024)
        val input = DataInputStream(ByteArrayInputStream(exported))
        input.readUTF()
        assertTrue(input.readInt() in 1..32)
        assertTrue(input.readInt() > 1)
        assertEquals(1002, input.readInt())
        assertEquals(123, input.readInt())
    }

    @Test fun `capture stops after two minutes and expires after ten minutes`() {
        var now = 100L
        val capture = VisualDiagnostics { now }
        capture.start()
        capture.record(65, byteArrayOf(1))
        now += 120_000
        capture.record(1003, byteArrayOf(2))
        val input = DataInputStream(ByteArrayInputStream(checkNotNull(capture.export())))
        input.readUTF()
        assertEquals(1, input.readInt())
        now += 480_000
        assertNull(capture.export())
    }

    @Test fun `clear and restart remove private data and export does not alias input`() {
        val capture = VisualDiagnostics()
        capture.start()
        val bytes = byteArrayOf(7)
        capture.record(65, bytes)
        bytes[0] = 9
        assertEquals(7, checkNotNull(capture.export()).last().toInt())
        capture.clear()
        capture.record(65, bytes)
        assertNull(capture.export())
        capture.start()
        capture.record(1003, bytes)
        capture.start()
        assertNull(capture.export())
    }

    @Test fun `capture is off by default and requires explicit arming`() {
        val capture = VisualDiagnostics()
        capture.record(65, byteArrayOf(1, 2))
        assertNull(capture.export())
        capture.start()
        capture.record(65, byteArrayOf(1, 2))
        val input = DataInputStream(ByteArrayInputStream(checkNotNull(capture.export())))
        assertEquals("VISOR-VISUAL-1", input.readUTF())
        assertEquals(1, input.readInt())
        assertEquals(0, input.readInt()) // Number of records rejected by the bounds.
        assertEquals(65, input.readInt())
        assertEquals(0, input.readInt()) // Channel unknown for a standalone event body.
        input.readLong()
        assertEquals(2, input.readInt())
        assertEquals(1, input.readUnsignedByte())
        assertEquals(2, input.readUnsignedByte())
        assertEquals(-1, input.read())
    }
}

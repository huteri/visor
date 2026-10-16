package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.protocol.ThriftWriter
import org.junit.Assert.*
import org.junit.Test

class MmaiVisualRequestTest {
    // Synthetic outer Thrift only; this is NOT a real compressed MMAI request or its inner schema.
    @Test fun `only a canonical interaction UUID can bind an unsupported request`() {
        val id = "11111111-2222-4333-8444-555555555555"
        assertEquals(id, MmaiVisualRequest.interactionId(ThriftWriter().str(1, id).str(2, "opaque").done()))
        assertNull(MmaiVisualRequest.interactionId(ThriftWriter().str(1, "1-2-3-4-5").str(2, "opaque").done()))
        assertNull(MmaiVisualRequest.interactionId(ThriftWriter().str(2, "opaque").done()))
        assertNull(MmaiVisualRequest.interactionId(byteArrayOf(0x18)))
        assertNull(MmaiVisualRequest.interactionId(ByteArray(512 * 1024 + 1)))
    }
}

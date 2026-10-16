package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.assistant.AssistantMessageDispatcherTest.Companion.chunk
import com.macedonga.visor.core.assistant.AssistantMessageDispatcherTest.Companion.header
import com.macedonga.visor.core.protocol.Wire
import org.junit.Assert.*
import org.junit.Test

class AssistantChunksTest {
    private val data = byteArrayOf(10, 20, 30)

    @Test fun `only known target and positive bounded exact header schema is accepted`() {
        val valid = header(3)
        val invalid = listOf(
            header(3, target = 5002), header(0), header(1_048_577), header(3, token = ""),
            header(3, token = "t".repeat(65)),
            Wire.fieldVarint(1, 1010) + Wire.fieldVarint(2, 3),
            Wire.fieldVarint(2, 3) + Wire.fieldBytes(3, "synthetic".toByteArray()),
            Wire.fieldBytes(1, byteArrayOf(1)) + Wire.fieldVarint(2, 3) + Wire.fieldBytes(3, "synthetic".toByteArray()),
            Wire.fieldVarint(1, 1010) + Wire.fieldBytes(2, byteArrayOf(3)) + Wire.fieldBytes(3, "synthetic".toByteArray()),
            Wire.fieldVarint(1, 1010) + Wire.fieldVarint(2, 3) + Wire.fieldVarint(3, 1),
            valid + Wire.fieldVarint(2, 3), valid + Wire.fieldVarint(4, 1),
            valid + byteArrayOf(0), valid + byteArrayOf(0x80.toByte()),
            byteArrayOf(8) + ByteArray(12) { 0x80.toByte() },
            Wire.fieldVarint(1, 1010) + byteArrayOf(16) + Wire.encodeVarintUnsigned(Long.MIN_VALUE) + Wire.fieldBytes(3, "synthetic".toByteArray()),
        )
        invalid.forEachIndexed { i, payload ->
            val a = assembler()
            assertNull(a.accept(1002, payload, 6))
            assertNull("invalid header $i", a.accept(1003, chunk(data, final = true), 6))
        }
    }

    @Test fun `invalid chunks cancel the transfer and cannot later be repaired`() {
        val token = Wire.fieldBytes(2, "synthetic".toByteArray())
        val invalid = listOf(
            chunk(byteArrayOf(1), token = "wrong"),
            Wire.fieldBytes(1, byteArrayOf(1)), token,
            Wire.fieldVarint(1, 1) + token,
            Wire.fieldBytes(1, byteArrayOf(1)) + Wire.fieldVarint(2, 1),
            chunk(byteArrayOf(1)) + Wire.fieldBytes(2, "synthetic".toByteArray()),
            chunk(byteArrayOf(1)) + Wire.fieldVarint(3, 2),
            chunk(byteArrayOf(1)) + Wire.fieldBytes(3, byteArrayOf(1)),
            chunk(byteArrayOf(1)) + Wire.fieldVarint(3, 0) + Wire.fieldVarint(3, 1),
            chunk(byteArrayOf(1)) + Wire.fieldVarint(4, 0),
            chunk(byteArrayOf()),
            // Declares four bytes but supplies one: Wire.parse deliberately truncates this.
            token + byteArrayOf(10, 4, 1),
            chunk(byteArrayOf(1)) + byteArrayOf(0x80.toByte()),
        )
        invalid.forEachIndexed { i, payload ->
            val a = assembler()
            a.accept(1002, header(3), 6)
            assertNull(a.accept(1003, payload, 6))
            assertNull("invalid chunk $i must invalidate header", a.accept(1003, chunk(data, final = true), 6))
        }
    }

    @Test fun `premature final overflow and exact length without final invalidate transfer`() {
        for (payload in listOf(chunk(byteArrayOf(1), final = true), chunk(data + 40), chunk(data))) {
            val a = assembler()
            a.accept(1002, header(3), 6)
            assertNull(a.accept(1003, payload, 6))
            assertNull(a.accept(1003, chunk(data, final = true), 6))
        }
    }

    @Test fun `unknown headers and channels never assemble partials`() {
        val a = assembler()
        assertNull(a.accept(1003, chunk(data, final = true), 6))
        a.accept(1002, header(3), 6)
        assertNull(a.accept(1003, chunk(data, final = true), 0x8006))
        assertArrayEquals(data, a.accept(1003, chunk(data, final = true), 6))
        assertNull(a.accept(1003, chunk(data, final = true), 6))
    }

    @Test fun `interleaved channels with the same token remain independent`() {
        val a = assembler()
        a.accept(1002, header(3), 6)
        a.accept(1002, header(3), 7)
        a.accept(1003, chunk(data.copyOfRange(0, 1)), 6)
        a.accept(1003, chunk(data.copyOfRange(0, 2)), 7)
        assertArrayEquals(data, a.accept(1003, chunk(data.copyOfRange(1, 3), final = true), 6))
        assertArrayEquals(data, a.accept(1003, chunk(data.copyOfRange(2, 3), final = true), 7))
    }

    @Test fun `new header supersedes old transfer including malformed replacement`() {
        val a = assembler()
        a.accept(1002, header(3, token = "old"), 6)
        a.accept(1003, chunk(byteArrayOf(1), token = "old"), 6)
        a.accept(1002, header(3), 6)
        assertArrayEquals(data, a.accept(1003, chunk(data, final = true), 6))
        a.accept(1002, header(3), 6)
        a.accept(1002, byteArrayOf(), 6)
        assertNull(a.accept(1003, chunk(data, final = true), 6))
    }

    @Test fun `memory count packet size and chunk count limits fail closed`() {
        val a = assembler(maxTransfers = 2, maxMessageBytes = 8, maxBufferedBytes = 4, maxChunks = 2)
        a.accept(1002, header(8), 6)
        a.accept(1002, header(8), 7)
        a.accept(1002, header(3), 8)
        assertNull(a.accept(1003, chunk(data, final = true), 8))
        a.accept(1003, chunk(data), 6)
        assertNull(a.accept(1003, chunk(data), 7)) // shared retained-byte budget
        assertNull(a.accept(1003, chunk(data, final = true), 7))
        assertNull(a.accept(1003, chunk(byteArrayOf(4)), 6))
        assertNull(a.accept(1003, chunk(byteArrayOf(5, 6, 7, 8), final = true), 6))
        a.accept(1002, header(3), 9)
        assertArrayEquals(data, a.accept(1003, chunk(data, final = true), 9)) // budget released

        val b = assembler(maxChunks = 2)
        b.accept(1002, header(3), 6)
        b.accept(1003, chunk(byteArrayOf(1)), 6)
        b.accept(1003, chunk(byteArrayOf(2)), 6)
        assertNull(b.accept(1003, chunk(byteArrayOf(3), final = true), 6))
        b.accept(1002, header(3), 6)
        assertNull(b.accept(1003, ByteArray(16_384), 6))
        assertNull(b.accept(1003, chunk(data, final = true), 6))
    }

    @Test fun `header deadlines are absolute and expire at boundary without a timer`() {
        var now = 10L
        val a = assembler(clock = { now }, timeoutMs = 20)
        a.accept(1002, header(3), 6)
        now = 29
        a.accept(1003, chunk(byteArrayOf(1)), 6)
        now = 30
        assertNull(a.accept(1003, chunk(byteArrayOf(2, 3), final = true), 6))
        a.accept(1002, header(3), 6)
        assertArrayEquals(data, a.accept(1003, chunk(data, final = true), 6))
        a.accept(1002, header(3), 7)
        now = 29 // A broken clock must not extend a transfer either.
        assertNull(a.accept(1003, chunk(data, final = true), 7))
    }

    @Test fun `clear and closed channel discard retained state`() {
        val a = assembler()
        a.accept(1002, header(3), 6)
        a.accept(1002, header(3), 7)
        a.clearChannel(6)
        assertNull(a.accept(1003, chunk(data, final = true), 6))
        assertArrayEquals(data, a.accept(1003, chunk(data, final = true), 7))
        a.accept(1002, header(3), 6)
        a.clear()
        assertNull(a.accept(1003, chunk(data, final = true), 6))
    }

    private fun assembler(
        clock: () -> Long = { 0L }, maxMessageBytes: Int = 1_048_576,
        maxBufferedBytes: Int = 2_097_152, maxTransfers: Int = 4, maxChunks: Int = 2048,
        timeoutMs: Long = 15_000,
    ) = AssistantChunks(clock, maxMessageBytes, maxBufferedBytes, maxTransfers, maxChunks, timeoutMs)
}

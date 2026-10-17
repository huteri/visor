package com.macedonga.visor.core.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProgressEncoderTest {
    @Test fun `incremental pump uses monotonic sample timestamps one EOS and bounded blocks`() = runBlocking {
        val inputs = mutableListOf<Pair<Long, Boolean>>()
        var pending: CodecOutput? = null
        var closed = 0
        val port = object : ProgressCodec {
            override fun offer(pcm: ShortArray, ptsUs: Long, end: Boolean): Boolean {
                inputs += ptsUs to end
                pending = CodecOutput(if (end) null else OpusPacket(byteArrayOf(0), 20_000), end)
                return true
            }
            override fun poll(): CodecOutput? = pending.also { pending = null }
            override fun close() { closed++ }
        }
        var ms = 0L
        val encoder = ProgressEncoderPump(port, { ms }, { ms += it })
        assertEquals(1, encoder.encode(ShortArray(960) { 1 }).size)
        assertEquals(1, encoder.encode(ShortArray(960)).size)
        encoder.encode(ShortArray(0), true)
        encoder.close()
        assertEquals(listOf(0L to false, 20_000L to false, 40_000L to true), inputs)
        assertEquals(1, closed)
        try { encoder.encode(ShortArray(960)); fail("EOS is terminal") } catch (_: IllegalStateException) { }
    }
}

package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.protocol.Thrift
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AnswerWireTest {
    private data class Write(val channel: Int, val bytes: ByteArray, val type: Int?) {
        val event: Int? get() = if (type == MT_NET_EVENT) parseNetEvent(bytes).id else null
    }

    @Test fun nativeTextPrecedesVarmOnlyWithoutSimplifiedForwarding() = runBlocking {
        for (simplified in listOf(true, false)) {
            val writes = mutableListOf<Write>()
            val wire = AnswerWire(simplified, {}) { channel, bytes, type -> writes += Write(channel, bytes, type) }
            wire.native(1, "interaction", "question", "actual answer", "selected-voice")
            assertEquals("simplified=$simplified", if (simplified) listOf(53, 60) else listOf(60, 53), writes.map { it.event })
            assertTrue(writes.all { it.channel == 1 && it.type == MT_NET_EVENT })
            val complete = parseNetEvent(writes.single { it.event == 60 }.bytes)
            assertEquals("interaction", Thrift.loads(complete.body)[1])
            assertTrue(writes.single { it.event == 53 }.bytes.toString(Charsets.ISO_8859_1).contains("selected-voice"))
        }
    }

    @Test fun phoneVarmFollowsStreamEndOnlyWithoutSimplifiedForwarding() = runBlocking {
        for (simplified in listOf(true, false)) {
            var ms = 0L
            val writes = mutableListOf<Write>()
            val wire = AnswerWire(simplified, { ms += it }) { channel, bytes, type -> writes += Write(channel, bytes, type) }
            val answer = netEvent(EV_VOICE_ACTION_RESPONSE, varm("interaction", "question", "answer"))
            wire.phone(1, 2, answer, List(4) { OpusPacket(byteArrayOf(7), 20_000) }) { ms }
            assertEquals("simplified=$simplified",
                if (simplified) listOf(MT_STREAM_AUDIO, MT_NET_EVENT, null, MT_STREAM_END)
                else listOf(MT_STREAM_AUDIO, null, MT_STREAM_END, MT_NET_EVENT), writes.map { it.type })
            assertEquals(if (simplified) listOf(2, 1, 2, 2) else listOf(2, 2, 2, 1), writes.map { it.channel })
            assertArrayEquals(byteArrayOf(1, 0, 0, 0, 7, 1, 0, 0, 0, 7), writes.first().bytes)
            assertEquals(53, writes.single { it.channel == 1 }.event)
            assertEquals(80L, ms)
        }
    }
}

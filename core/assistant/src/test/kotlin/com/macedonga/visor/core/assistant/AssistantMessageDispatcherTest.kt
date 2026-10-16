package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.protocol.Thrift
import com.macedonga.visor.core.protocol.ThriftWriter
import com.macedonga.visor.core.protocol.Wire
import java.util.zip.DeflaterOutputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class AssistantMessageDispatcherTest {
    @Test fun `direct enveloped and chunked requests reach the same event57 transcript handler`() = runBlocking {
        val raw = request()
        for (compressed in listOf(false, true)) {
            val blob = if (compressed) compressedRequest() else raw
            for (enveloped in listOf(false, true)) {
                val h = Harness()
                assertTrue(h.send(MT_NET_EVENT, blob, enveloped = enveloped))
                assertEquals(listOf("synthetic question"), h.transcripts)
                assertEquals(if (enveloped) 1 else 0, h.acks.size)

                val chunks = blob.toList().chunked(32).map { it.toByteArray() }
                val c = Harness()
                c.send(1002, header(blob.size), enveloped = enveloped)
                assertTrue(c.transcripts.isEmpty())
                chunks.forEachIndexed { i, bytes ->
                    c.send(1003, chunk(bytes, final = i == chunks.lastIndex), enveloped = enveloped)
                    assertEquals(if (i == chunks.lastIndex) 1 else 0, c.transcripts.size)
                }
                assertEquals(h.transcripts, c.transcripts)
                c.send(1003, chunk(chunks.last(), final = true), enveloped = enveloped)
                assertEquals(1, c.transcripts.size)
                assertEquals(if (enveloped) chunks.size + 2 else 0, c.acks.size)
                assertTrue(c.other.isEmpty())
            }
        }
    }

    @Test fun `captured six record shape dispatches only after the 113 byte final chunk`() = runBlocking {
        fun paddedRequest(padding: Int): ByteArray = netEvent(EV_FULFILLMENT_REQUEST,
            ThriftWriter().str(1, "synthetic-interaction")
                .struct(2, ThriftWriter().struct(1, ThriftWriter().str(1, "synthetic question").done()).done())
                .str(99, ByteArray(padding)).done())
        // Reproduce only the observed structural metadata, never the private captured body.
        val blob = paddedRequest(4209 - paddedRequest(1024).size + 1024)
        assertEquals(4209, blob.size)
        val h = Harness()
        h.send(1002, header(blob.size))
        val parts = blob.toList().chunked(1024).map { it.toByteArray() }
        assertEquals(listOf(1024, 1024, 1024, 1024, 113), parts.map { it.size })
        parts.forEachIndexed { index, part ->
            h.send(1003, chunk(part, final = index == parts.lastIndex))
            assertEquals(if (index == parts.lastIndex) 1 else 0, h.transcripts.size)
        }
        assertEquals(listOf("synthetic question"), h.transcripts)
        assertTrue(h.acks.isEmpty())
    }

    @Test fun `envelopes acknowledge once before dispatch and preserve non event traffic`() = runBlocking {
        val h = Harness()
        h.send(MT_START_INTERACTION, byteArrayOf(1), enveloped = true)
        assertEquals(listOf("ack", "other"), h.order)
        assertEquals(listOf(MT_START_INTERACTION), h.other)
        assertFalse(h.send(MT_STREAM_AUDIO, byteArrayOf(2)))
        assertEquals(1, h.acks.size)
        h.dispatcher.dispatch(MT_ENVELOPE, Wire.fieldVarint(2, MT_NET_EVENT.toLong()) + Wire.fieldBytes(3, request()), 6,
            acknowledge = { fail("no sequence must mean no ack") }, onEvent = { _, _ -> })
        Unit
    }

    @Test fun `malformed complete carriers and wrappers never escape or dispatch`() = runBlocking {
        val h = Harness()
        for (enveloped in listOf(false, true)) {
            h.send(MT_NET_EVENT, byteArrayOf(0xff.toByte()), enveloped = enveloped)
            h.send(1002, header(1), enveloped = enveloped)
            h.send(1003, chunk(byteArrayOf(0xff.toByte()), final = true), enveloped = enveloped)
            h.send(1002, byteArrayOf(0x80.toByte()), enveloped = enveloped)
        }
        h.send(MT_ENVELOPE, byteArrayOf(0x80.toByte()))
        assertTrue(h.transcripts.isEmpty())
    }

    @Test fun `closing a channel or link discards partials and reconnect gets fresh state`() = runBlocking {
        val h = Harness()
        val blob = request()
        h.send(1002, header(blob.size))
        h.send(1003, chunk(blob.copyOfRange(0, 10)))
        h.dispatcher.closeChannel(6)
        h.send(1003, chunk(blob.copyOfRange(10, blob.size), final = true))
        assertTrue(h.transcripts.isEmpty())
        h.send(1002, header(blob.size))
        h.dispatcher.close()
        h.send(1003, chunk(blob, final = true))
        h.send(MT_NET_EVENT, blob)
        h.send(1002, header(blob.size))
        h.send(1003, chunk(blob, final = true))
        assertTrue(h.transcripts.isEmpty())
        val reconnected = Harness()
        reconnected.send(1003, chunk(blob, final = true))
        assertTrue(reconnected.transcripts.isEmpty())
        reconnected.send(1002, header(blob.size))
        reconnected.send(1003, chunk(blob, final = true))
        assertEquals(listOf("synthetic question"), reconnected.transcripts)
    }

    @Test fun `link closed while envelope ack suspends cannot dispatch afterward`() = runBlocking {
        val d = AssistantMessageDispatcher { 0L }
        val blob = request()
        val ackStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseAck = kotlinx.coroutines.CompletableDeferred<Unit>()
        d.dispatch(1002, header(blob.size), 6, onEvent = { _, _ -> fail("header dispatched") })
        val child = launch {
            d.dispatch(MT_ENVELOPE, envelope(1003, chunk(blob, final = true)), 6,
                acknowledge = { ackStarted.complete(Unit); releaseAck.await() },
                onEvent = { _, _ -> fail("closed link dispatched") })
        }
        ackStarted.await()
        d.close()
        releaseAck.complete(Unit)
        child.join()
    }

    @Test fun `cancelled reader cannot dispatch a completed transfer after ack`() = runBlocking {
        val d = AssistantMessageDispatcher { 0L }
        val blob = request()
        d.dispatch(1002, header(blob.size), 6, onEvent = { _, _ -> fail("header dispatched") })
        val child = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
            d.dispatch(MT_ENVELOPE, envelope(1003, chunk(blob, final = true)), 6,
                acknowledge = { kotlinx.coroutines.currentCoroutineContext().cancel() },
                onEvent = { _, _ -> fail("cancelled reader dispatched") })
        }
        child.join()
        assertTrue(child.isCancelled)
    }

    @Test fun `partial wrappers may cross direct and envelope transport without a second ack`() = runBlocking {
        val h = Harness()
        val blob = request()
        h.send(1002, header(blob.size), enveloped = true)
        h.send(1003, chunk(blob.copyOfRange(0, 10)))
        h.send(MT_NET_EVENT, blob) // Unrelated traffic remains dispatchable during assembly.
        h.send(1003, chunk(blob.copyOfRange(10, blob.size), final = true), enveloped = true)
        assertEquals(listOf("synthetic question", "synthetic question"), h.transcripts)
        assertEquals(2, h.acks.size)
    }

    @Test fun `optional private capture sees direct and enveloped wrappers but not reconstructed bodies`() = runBlocking {
        val d = AssistantMessageDispatcher { 0L }
        val captured = mutableListOf<Pair<Int, Int>>()
        val blob = request()
        for (enveloped in listOf(false, true)) {
            for ((type, payload) in listOf(1002 to header(blob.size), 1003 to chunk(blob, final = true))) {
                d.dispatch(if (enveloped) MT_ENVELOPE else type,
                    if (enveloped) envelope(type, payload) else payload, 6,
                    onChunk = { capturedType, capturedBytes, channel ->
                        assertEquals(6, channel)
                        assertArrayEquals(payload, capturedBytes)
                        captured += capturedType to channel
                    }, onEvent = { _, _ -> })
            }
        }
        assertEquals(listOf(1002, 1003, 1002, 1003), captured.map { it.first })
    }

    private class Harness {
        val dispatcher = AssistantMessageDispatcher { 0L }
        val transcripts = mutableListOf<String>()
        val acks = mutableListOf<Long>()
        val other = mutableListOf<Int>()
        val order = mutableListOf<String>()
        suspend fun send(type: Int, bytes: ByteArray, channel: Int = 6, enveloped: Boolean = false): Boolean {
            val payload = if (enveloped) envelope(type, bytes, 7) else bytes
            return dispatcher.dispatch(if (enveloped) MT_ENVELOPE else type, payload, channel,
                acknowledge = { acks += it; order += "ack" },
                onEvent = { event, receivedChannel ->
                    order += "event"
                    assertEquals(channel, receivedChannel)
                    assertEquals(EV_FULFILLMENT_REQUEST, event.id)
                    val fields = Thrift.loads(event.body)
                    transcripts += fields[2].asStruct()[1].asStruct()[1].asText()
                },
                onEnvelope = { inner, _ -> other += inner; order += "other" })
        }
    }

    companion object {
        fun header(size: Int, token: String = "synthetic", target: Int = MT_NET_EVENT): ByteArray =
            Wire.fieldVarint(1, target.toLong()) + Wire.fieldVarint(2, size.toLong()) + Wire.fieldBytes(3, token.toByteArray())
        fun chunk(bytes: ByteArray, token: String = "synthetic", final: Boolean = false): ByteArray =
            Wire.fieldBytes(1, bytes) + Wire.fieldBytes(2, token.toByteArray()) +
                if (final) Wire.fieldVarint(3, 1) else ByteArray(0)
        fun envelope(type: Int, bytes: ByteArray, seq: Long = 1): ByteArray =
            Wire.fieldVarint(1, seq) + Wire.fieldVarint(2, type.toLong()) + Wire.fieldBytes(3, bytes)
        fun request(): ByteArray = netEvent(EV_FULFILLMENT_REQUEST, requestBody())
        private fun requestBody(): ByteArray = ThriftWriter().str(1, "synthetic-interaction")
            .struct(2, ThriftWriter().struct(1, ThriftWriter().str(1, "synthetic question").done()).done()).done()
        private fun compressedRequest(): ByteArray {
            val body = requestBody()
            val bytes = ByteArrayOutputStream()
            DeflaterOutputStream(bytes).use { it.write(body) }
            return ThriftWriter().i32(1, EV_FULFILLMENT_REQUEST).str(2, bytes.toByteArray())
                .bool(3, true).i64(4, body.size.toLong()).done()
        }
    }
}

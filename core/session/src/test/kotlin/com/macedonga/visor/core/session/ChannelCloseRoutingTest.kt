package com.macedonga.visor.core.session

import com.macedonga.visor.core.protocol.Header
import com.macedonga.visor.core.transport.Transport
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChannelCloseRoutingTest {
    @Test fun `receive and poll route service-less closes to the former owner without rebinding`() = runBlocking {
        for (pollClose in listOf(false, true)) {
            val h = Harness()
            val received = mutableListOf<DataXMessage>()
            h.session.addResponder(15) { message, _ -> received += message }
            val opening = frame(0x8006, type = 1002, service = 15)
            val close = closeFrame(0x8006)
            if (pollClose) {
                h.link.frames.send(opening + close)
            } else {
                h.link.frames.send(opening)
                h.link.frames.send(close)
            }
            h.route(h.mux.receive())
            val closing = if (pollClose) checkNotNull(h.mux.poll()) else h.mux.receive()
            assertTrue(closing.info.flags.isClosed)
            assertEquals(0, closing.info.serviceId)
            assertEquals(0, h.registry.serviceForInbound(0x8006))
            h.route(closing)
            assertEquals("close must reach the former owner (poll=$pollClose)", 2, received.size)
            assertTrue(received.last().info.flags.isClosed)
            assertEquals(0, h.registry.serviceForInbound(0x8006))
            h.link.frames.send(frame(0x8006))
            h.route(h.mux.receive())
            assertEquals("closed channel must not remain bound", 2, received.size)
        }
    }

    @Test fun `close keeps peer and local channels with the same low bits separate`() = runBlocking {
        val h = Harness()
        val assistant = mutableListOf<DataXMessage>()
        val device = mutableListOf<DataXMessage>()
        h.session.addResponder(15) { message, _ -> assistant += message }
        h.session.addResponder(28) { message, _ -> device += message }
        val local = h.mux.openChannel(28) and 0x7FFF
        val peer = local or 0x8000
        h.link.frames.send(frame(peer, type = 1002, service = 15) + frame(local, type = 101) +
            closeFrame(peer) + frame(local))
        h.route(h.mux.receive())
        while (h.mux.buffered) h.route(checkNotNull(h.mux.poll()))
        assertEquals(listOf(peer, peer), assistant.map { it.info.channelId })
        assertTrue(assistant.last().info.flags.isClosed)
        assertEquals(listOf(101, 101), device.map { it.info.messageType })
        assertEquals(0, h.registry.serviceForInbound(peer))
        assertEquals(28, h.registry.serviceForInbound(local))
        h.link.frames.send(closeFrame(local))
        h.route(h.mux.receive())
        assertEquals("other services keep baseline service-less close behavior", 2, device.size)
        assertFalse(device.last().info.flags.isClosed)
        assertEquals(0, h.registry.serviceForInbound(local))
    }

    @Test fun `close carrying a service tag preserves explicit delivery but never rebinds`() = runBlocking {
        for (service in listOf(15, 28)) {
            val h = Harness()
            val received = mutableListOf<DataXMessage>()
            h.session.addResponder(service) { message, _ -> received += message }
            h.link.frames.send(frame(0x8006, type = 1002, service = service))
            h.route(h.mux.receive())
            // A type-closed tag (4), alongside a nonzero service tag.
            val close = Header.build(0x8006, 0, serviceId = service, messageType = 1003)
            close[8] = 4
            h.link.frames.send(close)
            val closing = h.mux.receive()
            assertEquals(service, closing.info.serviceId)
            h.route(closing)
            assertEquals(2, received.size)
            assertTrue(received.last().info.flags.isClosed)
            assertEquals(0, h.registry.serviceForInbound(0x8006))
        }
    }

    @Test fun `pushed back close retains its former owner on delivery again`() = runBlocking {
        val h = Harness()
        val received = mutableListOf<DataXMessage>()
        h.session.addResponder(15) { message, _ -> received += message }
        h.link.frames.send(frame(0x8006, type = 1002, service = 15) + closeFrame(0x8006))
        h.route(h.mux.receive())
        val closing = checkNotNull(h.mux.poll())
        assertEquals(0, h.registry.serviceForInbound(0x8006))
        h.mux.pushback(listOf(closing))
        h.route(h.mux.receive())
        assertEquals(2, received.size)
        assertTrue(received.last().info.flags.isClosed)
        assertEquals(0, h.registry.serviceForInbound(0x8006))
    }

    @Test fun `deferred opening and close recover their owner when both are replayed`() = runBlocking {
        for (pollClose in listOf(false, true)) {
            val h = Harness()
            val received = mutableListOf<DataXMessage>()
            h.session.addResponder(15) { message, _ -> received += message }
            h.link.frames.send(frame(0x8006, type = 1002, service = 15) + closeFrame(0x8006))
            // Trust defers unrelated traffic without routing even the opening first.
            val opening = h.mux.receive()
            val closing = if (pollClose) checkNotNull(h.mux.poll()) else h.mux.receive()
            assertEquals(0, closing.closedServiceId)
            assertEquals(0, h.registry.serviceForInbound(0x8006))
            h.mux.pushback(listOf(opening, closing))
            h.route(h.mux.receive())
            assertEquals(15, h.registry.serviceForInbound(0x8006))
            h.route(h.mux.receive())
            assertEquals("replayed close must reach the former owner (poll=$pollClose)", 2, received.size)
            assertTrue(received.last().info.flags.isClosed)
            assertEquals(0, h.registry.serviceForInbound(0x8006))
            h.link.frames.send(frame(0x8006))
            h.route(h.mux.receive())
            assertEquals("replay must leave no residual binding", 2, received.size)
        }
    }

    @Test fun `reader queue closes are resolved at delivery after the opening is routed`() = runBlocking {
        val h = Harness()
        val received = mutableListOf<DataXMessage>()
        h.session.addResponder(15) { message, _ -> received += message }
        h.link.frames.send(frame(0x8006, type = 1002, service = 15) + closeFrame(0x8006))
        val reader = h.mux.startReader(this)
        try {
            h.route(h.mux.receive())
            h.route(checkNotNull(h.mux.poll()))
            assertEquals(2, received.size)
            assertTrue(received.last().info.flags.isClosed)
            assertEquals(0, h.registry.serviceForInbound(0x8006))
        } finally {
            reader.cancel()
            reader.join()
        }
    }

    @Test fun `ordinary service continuations retain cached type while statuses bypass responders`() = runBlocking {
        val h = Harness()
        val received = mutableListOf<DataXMessage>()
        h.session.addResponder(28) { message, _ -> received += message }
        h.link.frames.send(frame(0x8006, type = 101, service = 28) + frame(0x8006) +
            Header.build(0x8006, 0, errorId = 0x1000))
        h.route(h.mux.receive())
        h.route(checkNotNull(h.mux.poll()))
        val status = checkNotNull(h.mux.poll())
        h.route(status)
        assertEquals(listOf(101, 101), received.map { it.info.messageType })
        assertEquals(0, status.info.messageType)
        assertEquals(28, h.registry.serviceForInbound(0x8006))
        assertTrue(h.link.sent.isEmpty())
    }

    @Test fun `closing a refused peer service does not send another refusal`() = runBlocking {
        val h = Harness()
        h.link.frames.send(frame(0x8006, type = 1, service = 30) + closeFrame(0x8006))
        h.route(h.mux.receive())
        assertEquals(1, h.link.sent.size)
        h.route(checkNotNull(h.mux.poll()))
        assertEquals(1, h.link.sent.size)
        assertEquals(0, h.registry.serviceForInbound(0x8006))
    }

    private class Harness {
        val link = FakeLink()
        val registry = ChannelRegistry()
        val mux = DataXConnection(link, registry)
        val session = GlassesSession(object : Transport {
            override suspend fun connect(): Unit = error("not used")
            override suspend fun send(frame: ByteArray): Unit = error("not used")
            override suspend fun receive(): ByteArray = error("not used")
            override fun close() = Unit
        })

        init {
            // Exercise the production routing boundary without a hardware handshake or public test API.
            for ((name, value) in listOf("registry" to registry, "connection" to mux)) {
                GlassesSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
        }

        suspend fun route(message: DataXMessage): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                GlassesSession::class.java.getDeclaredMethod(
                    "handleMessage", DataXMessage::class.java, Continuation::class.java,
                ).apply { isAccessible = true }.invoke(session, message, continuation)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }

    private class FakeLink : PlaintextLink {
        val frames = Channel<ByteArray>(Channel.UNLIMITED)
        val sent = mutableListOf<ByteArray>()
        override suspend fun send(plaintext: ByteArray) { sent += plaintext }
        override suspend fun receive(): ByteArray = frames.receive()
    }

    companion object {
        private fun frame(channel: Int, type: Int? = null, service: Int? = null): ByteArray =
            Header.build(channel, 0, serviceId = service, messageType = type)

        private fun closeFrame(channel: Int): ByteArray = Header.build(channel, 0, serviceId = 0)
    }
}

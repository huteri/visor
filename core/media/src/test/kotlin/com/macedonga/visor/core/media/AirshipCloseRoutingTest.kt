package com.macedonga.visor.core.media

import com.macedonga.visor.core.protocol.Header
import com.macedonga.visor.core.session.ChannelRegistry
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.GlassesSession
import com.macedonga.visor.core.session.PlaintextLink
import com.macedonga.visor.core.transport.Transport
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AirshipCloseRoutingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `ordinary close after OFFER does not produce another Airship reply`() = runBlocking {
        verifyClose(afterCheck = false)
    }

    @Test fun `ordinary close after CHECK does not produce another Airship reply`() = runBlocking {
        verifyClose(afterCheck = true)
    }

    private suspend fun verifyClose(afterCheck: Boolean) {
        val h = Harness()
        val airship = AirshipReceiver(temporary.newFolder())
        h.session.addResponder(SVC_AIRSHIP, airship)
        try {
            val channel = 0x8006
            val offer = ProtoWriter().bytes(1, ByteArray(16) { it.toByte() }).varint(2, 4)
                .string(3, NAMESPACE_MEDIA).string(4, "synthetic.jpg").build()
            h.link.frames.send(Header.build(channel, offer.size, serviceId = SVC_AIRSHIP, messageType = 1) + offer)
            h.route(h.mux.receive())
            assertEquals(listOf(2), h.link.sent.map { Header.parse(it).messageType })
            assertEquals(1, airship.transfers.value.size)
            if (afterCheck) {
                h.link.frames.send(Header.build(channel, 0, messageType = 5))
                h.route(h.mux.receive())
                assertEquals(listOf(2, 2), h.link.sent.map { Header.parse(it).messageType })
            }
            val beforeClose = h.link.sent.size
            h.link.frames.send(Header.build(channel, 0, serviceId = 0))
            val closing = h.mux.receive()
            assertTrue(closing.info.flags.isClosed)
            assertEquals(0, closing.info.serviceId)
            assertEquals(if (afterCheck) 5 else 1, closing.info.messageType)
            h.route(closing)
            assertEquals("a lifecycle close must not become a fresh DROP or CURSOR", beforeClose, h.link.sent.size)
            assertEquals(0, h.registry.serviceForInbound(channel))
            assertEquals("ordinary close preserves Airship's existing transfer lifecycle", 1, airship.transfers.value.size)
        } finally {
            airship.abandon()
        }
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
            // Keep the actual mux, session router, and Airship responder; omit only the handshake.
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
}

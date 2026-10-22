package com.macedonga.visor.core.media

import com.macedonga.visor.core.protocol.Header
import com.macedonga.visor.core.session.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CameraServiceDestructionTest {
    private class Link : PlaintextLink {
        val sent = mutableListOf<ByteArray>()
        override suspend fun send(plaintext: ByteArray) { sent += plaintext }
        override suspend fun receive(): ByteArray = error("No close frame expected")
    }

    @Test fun staleMuxAndPeerOriginAndOtherStopReasonsCannotRetireReplacement() = runBlocking {
        val receiver = StreamReceiver()
        val oldLink = Link()
        val currentLink = Link()
        val oldMux = DataXConnection(oldLink, ChannelRegistry())
        val current = DataXConnection(currentLink, ChannelRegistry())
        suspend fun stopped(mux: DataXConnection, channel: Int, reason: Int) {
            val payload = byteArrayOf(0x10, reason.toByte())
            receiver.respond(DataXMessage(Header.parse(Header.build(channel, payload.size, messageType = StreamType.CONTROL_STREAM_STOPPED)), payload), mux)
        }
        oldMux.sendOnService(51350, 1, byteArrayOf(7))
        current.sendOnService(51350, 1, byteArrayOf(7))
        val first = Header.parse(currentLink.sent.last()).channelId
        stopped(oldMux, DataXConnection.mirror(first), 23)
        stopped(current, first, 23)
        stopped(current, DataXConnection.mirror(first), 1)
        current.sendOnService(51350, 2, byteArrayOf(7))
        assertEquals(first, Header.parse(currentLink.sent.last()).channelId)
        val sendsAtDestruction = currentLink.sent.size
        stopped(current, DataXConnection.mirror(first), 23)
        assertEquals("Destruction must not auto reopen camera", sendsAtDestruction, currentLink.sent.size)
        current.sendOnService(51350, 1, byteArrayOf(7))
        val replacement = Header.parse(currentLink.sent.last()).channelId
        assertNotEquals(first, replacement)
        stopped(current, DataXConnection.mirror(first), 23)
        current.sendOnService(51350, 2, byteArrayOf(7))
        assertEquals(replacement, Header.parse(currentLink.sent.last()).channelId)
    }

    @Test fun threeCapturesAfterServiceDestructionWithoutCloseUseFreshChannels() = runBlocking {
        val link = Link()
        val mux = DataXConnection(link, ChannelRegistry())
        val receiver = StreamReceiver()
        val channels = mutableSetOf<Int>()
        repeat(3) {
            mux.sendOnService(51350, 1, byteArrayOf(7))
            val opened = Header.parse(link.sent.last())
            assertTrue("Destroyed service must not reuse retired channel", channels.add(opened.channelId))
            assertEquals(51350, opened.serviceId)
            mux.sendOnService(51350, 2, byteArrayOf(7))
            assertEquals(opened.channelId, Header.parse(link.sent.last()).channelId)
            assertFalse(Header.parse(link.sent.last()).flags.setService)
            val payload = byteArrayOf(0x10, 23)
            receiver.respond(DataXMessage(Header.parse(Header.build(DataXConnection.mirror(opened.channelId), payload.size, messageType = StreamType.CONTROL_STREAM_STOPPED)), payload), mux)
        }
    }
}

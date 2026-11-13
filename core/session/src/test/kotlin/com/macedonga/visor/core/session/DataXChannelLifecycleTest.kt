package com.macedonga.visor.core.session

import com.macedonga.visor.core.protocol.Header
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import org.junit.Assert.*
import org.junit.Test

class DataXChannelLifecycleTest {
    private interface PlaintextLink { suspend fun send(plaintext: ByteArray); suspend fun receive(): ByteArray }
    private fun mux(link: PlaintextLink, registry: ChannelRegistry, channelTrace: ((String) -> Unit)? = null): DataXConnection {
        val secure = org.mockito.Mockito.mock(SecureLink::class.java) { call ->
            when (call.method.name) {
                "send" -> {
                    @Suppress("UNCHECKED_CAST") val continuation = call.rawArguments.last() as kotlin.coroutines.Continuation<Unit>
                    val write: suspend () -> Unit = { link.send(call.getArgument(0)) }
                    write.startCoroutineUninterceptedOrReturn(continuation)
                }
                "nextSdu" -> kotlinx.coroutines.runBlocking { link.receive() }
                "open" -> call.getArgument<ByteArray>(0)
                else -> org.mockito.Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        return DataXConnection(secure, registry, channelTrace = channelTrace)
    }
    private class Link : PlaintextLink {
        val sent = mutableListOf<ByteArray>()
        val incoming = ArrayDeque<ByteArray>()
        override suspend fun send(plaintext: ByteArray) { sent += plaintext }
        override suspend fun receive() = incoming.removeFirst()
    }

    @Test fun cameraChannelMetadataIsBoundedAndContainsNoPayload() = runBlocking {
        val link = Link()
        val metadata = mutableListOf<String>()
        val mux = mux(link, ChannelRegistry(), channelTrace = { metadata += it })
        repeat(20) { mux.sendOnService(51350, 1, "private-payload".toByteArray()) }
        assertEquals(1, metadata.size)
        assertTrue(metadata.first().contains("opened"))
        val old = Header.parse(link.sent.first()).channelId
        assertTrue(mux.invalidateServiceChannel(51350, DataXConnection.mirror(old)))
        mux.sendOnService(51350, 1, byteArrayOf(7))
        val fresh = Header.parse(link.sent.last()).channelId
        link.incoming += Header.build(DataXConnection.mirror(fresh), 0, serviceId = 0)
        mux.receive()
        assertTrue(metadata.any { it.contains("invalidated") && it.contains("reused=19") })
        assertTrue(metadata.last().contains("peer-closed"))
        assertTrue(metadata.none { it.contains("private-payload") })
    }

    @Test fun suspendedSendCannotRestoreInvalidatedCache() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sent = mutableListOf<ByteArray>()
        val link = object : PlaintextLink {
            override suspend fun send(plaintext: ByteArray) {
                sent += plaintext
                if (sent.size == 1) { entered.complete(Unit); release.await() }
            }
            override suspend fun receive(): ByteArray = error("unused")
        }
        val mux = mux(link, ChannelRegistry())
        val sending = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            mux.sendOnService(51350, 1, byteArrayOf(7))
        }
        entered.await()
        val retired = Header.parse(sent.first()).channelId
        assertTrue(mux.invalidateServiceChannel(51350, DataXConnection.mirror(retired)))
        release.complete(Unit)
        sending.join()
        mux.sendOnService(51350, 1, byteArrayOf(7))
        val fresh = Header.parse(sent.last())
        assertNotEquals(retired, fresh.channelId)
        assertEquals(51350, fresh.serviceId)
        release.complete(Unit)
        sending.join()
        assertFalse(mux.invalidateServiceChannel(51350, DataXConnection.mirror(retired)))
        mux.sendOnService(51350, 2, byteArrayOf(7))
        assertEquals(fresh.channelId, Header.parse(sent.last()).channelId)
        assertEquals(0, Header.parse(sent.last()).serviceId)
    }

    @Test fun cameraServiceReopensAfterFirstAndSecondPeerClose() = runBlocking {
        val link = Link()
        val mux = mux(link, ChannelRegistry())
        val channels = mutableSetOf<Int>()
        repeat(3) {
            mux.sendOnService(51350, 1, byteArrayOf(7))
            val first = Header.parse(link.sent.last())
            assertTrue("Each new capture must allocate fresh channel", channels.add(first.channelId))
            assertEquals("New channel must explicitly name camera service", 51350, first.serviceId)
            mux.sendOnService(51350, 2, byteArrayOf(7))
            assertEquals(first.channelId, Header.parse(link.sent.last()).channelId)
            assertEquals(0, Header.parse(link.sent.last()).serviceId)
            link.incoming += Header.build(DataXConnection.mirror(first.channelId), 0, serviceId = 0)
            assertTrue(mux.receive().info.flags.isClosed)
        }
    }

    @Test fun onlyCameraServiceNotFoundRetiresCacheButKeepsRegistryUntilClose() = runBlocking {
        val link = Link()
        val registry = ChannelRegistry()
        val mux = mux(link, registry)
        mux.sendOnService(51350, 1, byteArrayOf(7))
        val old = Header.parse(link.sent.last()).channelId
        val inbound = DataXConnection.mirror(old)
        for (status in listOf(0xc002, 0xc010)) {
            link.incoming += Header.build(inbound, 0, errorId = status)
            mux.receive()
            mux.sendOnService(51350, 2, byteArrayOf(7))
            assertEquals(old, Header.parse(link.sent.last()).channelId)
        }
        link.incoming += Header.build(old, 0, errorId = 0xc001)
        mux.receive()
        mux.sendOnService(51350, 2, byteArrayOf(7))
        assertEquals(old, Header.parse(link.sent.last()).channelId)
        mux.sendOnService(15, 1, byteArrayOf(7))
        val audio = Header.parse(link.sent.last()).channelId
        link.incoming += Header.build(DataXConnection.mirror(audio), 0, errorId = 0xc001)
        mux.receive()
        mux.sendOnService(15, 2, byteArrayOf(7))
        assertEquals(audio, Header.parse(link.sent.last()).channelId)
        link.incoming += Header.build(inbound, 0, errorId = 0xc001)
        mux.receive()
        assertEquals(51350, registry.serviceForInbound(inbound))
        mux.sendOnService(51350, 1, byteArrayOf(7))
        val fresh = Header.parse(link.sent.last())
        assertNotEquals(old, fresh.channelId)
        assertEquals(51350, fresh.serviceId)
        link.incoming += Header.build(inbound, 0, errorId = 0xc001)
        mux.receive()
        link.incoming += Header.build(inbound, 0, serviceId = 0)
        mux.receive()
        assertEquals(0, registry.serviceForInbound(inbound))
        mux.sendOnService(51350, 2, byteArrayOf(7))
        assertEquals(fresh.channelId, Header.parse(link.sent.last()).channelId)
    }

    @Test fun peerOriginSameNumberCloseAndStatusDoNotEvictLocalServices() = runBlocking {
        val link = Link()
        val mux = mux(link, ChannelRegistry())
        mux.sendOnService(51350, 1, byteArrayOf(7))
        val camera = Header.parse(link.sent.last()).channelId
        mux.sendOnService(15, 1, byteArrayOf(7))
        val audio = Header.parse(link.sent.last()).channelId
        link.incoming += Header.build(camera, 0, serviceId = 0)
        mux.receive()
        link.incoming += Header.build(DataXConnection.mirror(camera), 0, errorId = 0xc002)
        mux.receive()
        mux.sendOnService(51350, 2, byteArrayOf(7))
        assertEquals(camera, Header.parse(link.sent.last()).channelId)
        link.incoming += Header.build(DataXConnection.mirror(camera), 0, serviceId = 0)
        mux.receive()
        mux.sendOnService(15, 2, byteArrayOf(7))
        assertEquals(audio, Header.parse(link.sent.last()).channelId)
        assertEquals(0, Header.parse(link.sent.last()).serviceId)
    }
}

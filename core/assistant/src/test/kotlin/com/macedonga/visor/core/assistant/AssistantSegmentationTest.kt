package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import sun.misc.Unsafe

class AssistantSegmentationTest {
    private fun set(target: Any, field: String, value: Any) = target.javaClass.getDeclaredField(field).let { it.isAccessible = true; it.set(target, value) }
    private suspend fun scenario(protocol: Int, type: Int, revoke: Boolean = false) {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let { it.isAccessible = true; it.get(null) as Unsafe }
        val assistant = unsafe.allocateInstance(Assistant::class.java) as Assistant
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        var owned = true
        val mux = mock(DataXConnection::class.java) { invocation ->
            if (invocation.method.name == "send") {
                sent += invocation.getArgument<Int>(2) to invocation.getArgument<ByteArray>(1)
                if (revoke) owned = false
                Unit
            } else RETURNS_DEFAULTS.answer(invocation)
        }
        val linkClass = Class.forName("com.macedonga.visor.core.assistant.Assistant\$Link")
        val link = linkClass.getDeclaredConstructor(DataXConnection::class.java).let { it.isAccessible = true; it.newInstance(mux) }
        set(link, "protocol", protocol)
        val payload = ByteArray(2500) { (it % 251).toByte() }
        val validate: () -> Unit = { check(owned) { "revoked" } }
        val result = runCatching {
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val method = assistant.javaClass.declaredMethods.single { it.name == "sendNetEvent" }
                method.isAccessible = true
                try { method.invoke(assistant, link, 7, payload, type, validate, continuation) }
                catch (error: java.lang.reflect.InvocationTargetException) { throw error.cause!! }
            }
        }
        if (revoke) {
            assertTrue(result.isFailure)
            assertEquals(listOf(MT_SEGMENTED_HEADER), sent.map { it.first })
        } else if (protocol == 0) {
            result.getOrThrow()
            assertEquals(type, sent.single().first)
            assertArrayEquals(payload, sent.single().second)
        } else {
            result.getOrThrow()
            assertEquals(listOf(MT_SEGMENTED_HEADER, MT_SEGMENTED_BUFFER, MT_SEGMENTED_BUFFER, MT_SEGMENTED_BUFFER), sent.map { it.first })
            val header = Wire.parse(sent.first().second)
            assertEquals(MT_NET_EVENT.toLong(), header.varint(1))
            assertEquals(payload.size.toLong(), header.varint(2))
            val pieces = sent.drop(1).map { Wire.parse(it.second) }
            assertTrue(pieces.all { it.bytes(2).contentEquals(header.bytes(3)) })
            assertEquals(listOf(0L, 0L, 1L), pieces.map { it.varint(3) })
            assertArrayEquals(payload, pieces.fold(ByteArray(0)) { all, part -> all + part.bytes(1) })
        }
    }
    @Test fun protocolZeroKeepsLargeEventsWhole() = runBlocking { scenario(0, MT_NET_EVENT) }
    @Test fun protocolOneSegmentsNativeEvents() = runBlocking { scenario(1, MT_NET_EVENT) }
    @Test fun segmentedPreambleKeepsLogicalNetEventKind() = runBlocking { scenario(1, 0) }
    @Test fun revocationAfterHeaderPreventsEverySegment() = runBlocking { scenario(1, MT_NET_EVENT, true) }
}

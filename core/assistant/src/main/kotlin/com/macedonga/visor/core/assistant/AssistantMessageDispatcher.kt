package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.protocol.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One link's pure svc15 receive seam, also usable without constructing an Android Assistant. */
internal class AssistantMessageDispatcher(elapsedMs: () -> Long) {
    private val chunks = AssistantChunks(elapsedMs)
    @Volatile private var closed = false

    fun closeChannel(channel: Int) = chunks.clearChannel(channel)

    fun close() {
        closed = true
        chunks.clear()
    }

    suspend fun dispatch(
        type: Int,
        payload: ByteArray,
        channel: Int,
        acknowledge: suspend (Long) -> Unit = {},
        onEvent: suspend (NetEvent, Int) -> Unit,
        onEnvelope: suspend (Int, ByteArray) -> Unit = { _, _ -> },
        onChunk: (Int, ByteArray, Int) -> Unit = { _, _, _ -> },
    ): Boolean {
        try {
            currentCoroutineContext().ensureActive()
            if (closed) return true
            var inner = type
            var blob = payload
            if (type == MT_ENVELOPE) {
                val env = runCatching { Wire.parse(payload) }.getOrNull() ?: return true
                // Ack only the received envelope, never a reconstructed carrier or individual chunk.
                if (env.has(1)) acknowledge(env.varint(1))
                currentCoroutineContext().ensureActive()
                if (closed) return true
                inner = env.varint(2).toInt()
                blob = env.bytes(3)
            }
            when (inner) {
                1002, 1003 -> {
                    onChunk(inner, blob, channel)
                    val complete = chunks.accept(inner, blob, channel) ?: return true
                    blob = complete
                }
                MT_NET_EVENT -> Unit
                else -> {
                    if (type != MT_ENVELOPE) return false
                    onEnvelope(inner, blob)
                    return true
                }
            }
            // Malformed carrier errors can contain private data: neither log bytes nor error messages.
            val event = runCatching { parseNetEvent(blob) }.getOrNull() ?: return true
            currentCoroutineContext().ensureActive()
            if (!closed) onEvent(event, channel)
            return true
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        }
    }
}

package com.macedonga.visor.core.assistant

/**
 * Observed svc15 wrapper: 1002 = {1 target, 2 byte count, 3 token};
 * 1003 = {1 bytes, 2 token, 3 final (optional)}. There is no observed sequence/ack field.
 * One transfer per exact inbound channel; a new header supersedes it, never another channel.
 */
internal class AssistantChunks(
    private val elapsedMs: () -> Long,
    private val maxMessageBytes: Int = 1_048_576,
    private val maxBufferedBytes: Int = 2_097_152,
    private val maxTransfers: Int = 4,
    private val maxChunks: Int = 2048,
    private val timeoutMs: Long = 15_000,
) {
    private class Transfer(val size: Int, val token: ByteArray, val started: Long) {
        val parts = mutableListOf<ByteArray>()
        var received = 0
    }
    private val transfers = mutableMapOf<Int, Transfer>()
    private var retained = 0

    init {
        require(maxMessageBytes > 0 && maxBufferedBytes > 0 && maxTransfers > 0 && maxChunks > 0 && timeoutMs > 0)
    }

    @Synchronized fun clearChannel(channel: Int) {
        transfers.remove(channel)?.let { retained -= it.received }
    }

    @Synchronized fun clear() {
        transfers.clear()
        retained = 0
    }

    /** Expiry is lazy: no timer or queued completion can outlive a link. */
    @Synchronized fun accept(type: Int, payload: ByteArray, channel: Int): ByteArray? {
        val now = elapsedMs()
        transfers.keys.toList().forEach { id ->
            val transfer = transfers.getValue(id)
            if (now < transfer.started || now - transfer.started >= timeoutMs) clearChannel(id)
        }
        if (type != 1002 && type != 1003) return null
        if (type == 1002) clearChannel(channel)
        val fields = try {
            parse(payload, type)
        } catch (_: IllegalArgumentException) {
            clearChannel(channel)
            return null
        }
        if (type == 1002) {
            val target = fields[1] as? Long
            val size = fields[2] as? Long ?: 0L
            val token = fields[3] as? ByteArray ?: return null
            if (target != MT_NET_EVENT.toLong() || size !in 1..maxMessageBytes.toLong() ||
                token.isEmpty() || token.size > MAX_TOKEN_BYTES || transfers.size >= maxTransfers) return null
            // Retain only received bytes: never allocate an announced length.
            transfers[channel] = Transfer(size.toInt(), token, now)
            return null
        }
        val transfer = transfers[channel] ?: return null
        val bytes = fields[1] as? ByteArray
        val token = fields[2] as? ByteArray
        val final = fields[3] as? Long ?: 0L
        if (bytes == null || bytes.isEmpty() || token == null || !token.contentEquals(transfer.token) ||
            final !in 0L..1L || transfer.parts.size >= maxChunks ||
            bytes.size > transfer.size - transfer.received || bytes.size > maxBufferedBytes - retained) {
            clearChannel(channel)
            return null
        }
        val received = transfer.received + bytes.size
        if ((final == 1L) != (received == transfer.size)) {
            clearChannel(channel)
            return null
        }
        transfer.parts += bytes
        transfer.received = received
        retained += bytes.size
        if (final != 1L) return null
        clearChannel(channel)
        return ByteArray(received).also { result ->
            var offset = 0
            transfer.parts.forEach { part -> part.copyInto(result, offset); offset += part.size }
        }
    }

    // Wire.parse intentionally tolerates truncated lengths and hides field types/duplicates.
    // This small, bounded wrapper needs exact types, single fields, and complete consumption.
    private fun parse(payload: ByteArray, type: Int): Map<Int, Any> {
        require(payload.size <= MAX_PACKET_BYTES)
        var offset = 0
        fun varint(): Long {
            var value = 0L
            repeat(9) { index ->
                require(offset < payload.size)
                val b = payload[offset++].toInt() and 0xff
                value = value or ((b and 0x7f).toLong() shl (index * 7))
                if (b and 0x80 == 0) {
                    require(index == 0 || b != 0)
                    return value
                }
            }
            throw IllegalArgumentException()
        }
        val fields = mutableMapOf<Int, Any>()
        while (offset < payload.size) {
            val key = varint()
            val allowed = if (type == 1002) setOf(8L, 16L, 26L) else setOf(10L, 18L, 24L)
            require(key in allowed)
            val field = (key ushr 3).toInt()
            require(field !in fields)
            fields[field] = if (key and 7L == 0L) varint() else {
                val length = varint()
                require(length in 0..(payload.size - offset).toLong())
                val end = offset + length.toInt()
                payload.copyOfRange(offset, end).also { offset = end }
            }
        }
        return fields
    }

    private companion object {
        const val MAX_TOKEN_BYTES = 64
        const val MAX_PACKET_BYTES = 16_383
    }
}

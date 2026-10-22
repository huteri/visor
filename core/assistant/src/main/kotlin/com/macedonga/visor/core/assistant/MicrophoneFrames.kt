package com.macedonga.visor.core.assistant

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Validate a whole chunk before delivering anything; malformed suffixes must not leak partial audio. */
internal fun readMicrophoneFrames(chunk: ByteArray, decode: (ByteArray, Long?) -> Unit): Boolean {
    data class Frame(val from: Int, val size: Int, val ms: Long?)
    val frames = mutableListOf<Frame>()
    val b = ByteBuffer.wrap(chunk)
    if (chunk.isEmpty()) return false

    // Ordinary question: repeated BE32 packet lengths, without timestamps or ranges.
    if (chunk.size >= 5 && b.getInt(0) in 1..chunk.size - 4) {
        while (b.hasRemaining()) {
            if (b.remaining() < 4) return false
            val n = b.int
            if (n <= 0 || n > b.remaining()) return false
            frames += Frame(b.position(), n, null)
            b.position(b.position() + n)
        }
    } else {
        while (b.hasRemaining()) {
            if (b.remaining() < 12) return false
            b.order(ByteOrder.LITTLE_ENDIAN)
            val length = b.int
            // Outer length excludes its own four bytes. Subtraction avoids integer overflow.
            if (length < 8 || length > b.remaining()) return false
            val end = b.position() + length
            val ms = b.int.toLong() and 0xFFFFFFFFL
            val selector = b.int
            if (selector == 1 || selector == 2) {
                // Live AI: LE32 clock/selector, unsigned varint payload size, then BE32
                // Opus length + BE32 final decoder range + Opus. Zero payload is control only.
                var payload = 0L
                var shift = 0
                var terminated = false
                while (b.position() < end && shift <= 28) {
                    val v = b.get().toInt() and 0xFF
                    payload = payload or ((v and 0x7F).toLong() shl shift)
                    if (v and 0x80 == 0) {
                        terminated = true
                        break
                    }
                    shift += 7
                }
                if (!terminated || payload != (end - b.position()).toLong()) return false
                if (payload > 0) {
                    if (payload < 9) return false
                    b.order(ByteOrder.BIG_ENDIAN)
                    val n = b.int
                    b.int // final decoder range, not codec data
                    if (n <= 0 || n != end - b.position()) return false
                    frames += Frame(b.position(), n, ms)
                    b.position(end)
                }
            } else {
                // Existing follow-up/shutdown: BE32 packet length occupies selector's slot.
                b.order(ByteOrder.BIG_ENDIAN)
                val n = b.getInt(b.position() - 4)
                if (end - b.position() < 4) return false
                b.int // final decoder range
                if (n <= 0 || n != end - b.position()) return false
                frames += Frame(b.position(), n, ms)
                b.position(end)
            }
            if (b.position() != end) return false
        }
    }
    frames.forEach { decode(chunk.copyOfRange(it.from, it.from + it.size), it.ms) }
    return true
}

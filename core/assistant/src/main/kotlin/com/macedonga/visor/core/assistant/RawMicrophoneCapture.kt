package com.macedonga.visor.core.assistant

/** Explicit diagnostic APK opt-in. Ordinary release and debug versions fail closed. */
internal fun rawMicrophoneCaptureAllowed(versionName: String?): Boolean =
    versionName?.contains("-livediagnostic.") == true

/** Local Event log only. Never feed this sink to transport, analytics or Android Log.
 * Fixed sampling allocation: header 512 + first 64*192 + late 8*256 + tail 4*256 + shutdown 256
 * = 16128 raw bytes, at most 80 lines including summary. No caller-owned arrays retained.
 */
internal class RawMicrophoneCapture(
    private val enabled: Boolean,
    private val nowMs: () -> Long,
    private val note: (String) -> Unit,
) {
    private val started = nowMs()
    private var closed = false
    private var headerSeen = false
    private var index = 0L
    private var late = 0
    private var rawBytes = 0
    private var lines = 0
    private val tail = ArrayDeque<Sample>(4)
    private data class Sample(val bytes: ByteArray, val length: Int, val type: Int,
        val channel: Int, val extensions: Boolean, val index: Long, val elapsed: Long)

    private fun sample(bytes: ByteArray, type: Int, channel: Int, extensions: Boolean, limit: Int) =
        Sample(bytes.copyOf(minOf(bytes.size, limit)), bytes.size, type, channel, extensions,
            index, (nowMs() - started).coerceAtLeast(0))

    @Synchronized fun header(bytes: ByteArray, type: Int, channel: Int, extensions: Boolean) {
        if (!enabled || closed || headerSeen) return
        headerSeen = true
        val value = sample(bytes, type, channel, extensions, 512)
        for (offset in value.bytes.indices step 256) emit("HEADER", value, offset, 256)
        if (value.bytes.isEmpty()) emit("HEADER", value)
    }

    @Synchronized fun packet(bytes: ByteArray, type: Int, channel: Int, extensions: Boolean) {
        if (!enabled || closed) return
        index++
        val value = sample(bytes, type, channel, extensions, 256)
        if (tail.size == 4) tail.removeFirst()
        tail.addLast(value)
        if (index <= 64) emit("FIRST", value, limit = 192)
        else if (value.elapsed >= 3000 && late < 8) {
            late++
            emit("LATE", value)
        }
    }

    /** Call on receipt, before closing: preserves shutdown bytes and preceding ring. */
    @Synchronized fun shutdown(bytes: ByteArray, type: Int, channel: Int, extensions: Boolean) {
        if (!enabled || closed) return
        flushTail()
        emit("SHUTDOWN", sample(bytes, type, channel, extensions, 256))
        close()
    }

    @Synchronized fun close() {
        if (!enabled || closed) return
        closed = true
        flushTail()
        if (lines < 100) {
            note("RAW AUDIO SENSITIVE local-only kind=CLOSED packets=$index rawBytes=$rawBytes lines=${lines + 1} capBytes=16384 capLines=100")
            lines++
        }
    }

    private fun flushTail() {
        while (tail.isNotEmpty()) emit("TAIL", tail.removeFirst())
    }

    private fun emit(kind: String, value: Sample, offset: Int = 0, limit: Int = 256) {
        if (lines >= 99) return // reserve summary
        val size = minOf(limit, value.bytes.size - offset, 16384 - rawBytes).coerceAtLeast(0)
        val hex = buildString(size * 2) {
            repeat(size) {
                val b = value.bytes[offset + it].toInt() and 255
                append("0123456789abcdef"[b ushr 4]); append("0123456789abcdef"[b and 15])
            }
        }
        rawBytes += size
        lines++
        note("RAW AUDIO SENSITIVE local-only kind=$kind index=${value.index} elapsedMs=${value.elapsed} len=${value.length} type=${value.type} channel=${value.channel} hasExtensions=${value.extensions} offset=$offset captured=$size truncated=${offset + size < value.length} hex=$hex")
    }
}

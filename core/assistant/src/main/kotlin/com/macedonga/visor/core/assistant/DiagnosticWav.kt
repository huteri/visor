package com.macedonga.visor.core.assistant

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Strict bounded PCM reader. Validate the complete container before allocating audio. */
internal object DiagnosticWav {
    internal class SpeechTooLong : IllegalArgumentException("diagnostic speech too long")
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val OUTPUT_RATE = 48_000
    private val rates = setOf(8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)

    fun read(file: File, maxSeconds: Int, checkActive: () -> Unit = {}): ShortArray =
        readBoundedPcm(file, maxSeconds, checkActive)

    fun readBoundedPcm(file: File, maxSeconds: Int, checkActive: () -> Unit = {}): ShortArray {
        require(maxSeconds in 1..25)
        checkActive()
        return RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            require(length in 44..MAX_BYTES) { "diagnostic WAV size out of bounds" }
            fun u16(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
            fun u32(): Long = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            fun tag(): Int = input.readInt()
            require(tag() == 0x52494646 && u32() == length - 8 && tag() == 0x57415645) { "invalid diagnostic RIFF" }
            var at = 12L
            var channels = 0
            var rate = 0
            var dataAt = -1L
            var dataSize = 0L
            while (at < length) {
                checkActive()
                require(length - at >= 8) { "truncated diagnostic chunk" }
                input.seek(at)
                val id = tag()
                val size = u32()
                val body = at + 8
                require(size <= length - body) { "diagnostic chunk outside file" }
                val next = body + size + (size and 1)
                require(next > at && next <= length) { "invalid diagnostic chunk progress" }
                when (id) {
                    0x666d7420 -> {
                        require(rate == 0 && size >= 16) { "invalid diagnostic fmt" }
                        require(u16() == 1) { "diagnostic requires integer PCM" }
                        channels = u16()
                        val sampleRate = u32()
                        require(channels in 1..2 && sampleRate in rates.map { it.toLong() }) { "unsupported diagnostic PCM" }
                        rate = sampleRate.toInt()
                        val byteRate = u32()
                        val align = u16()
                        val bits = u16()
                        require(bits == 16 && align == channels * 2 && byteRate == rate.toLong() * align) { "invalid diagnostic PCM layout" }
                    }
                    0x64617461 -> {
                        require(rate != 0 && dataAt == -1L && size > 0 && size % (channels * 2) == 0L) { "invalid diagnostic data" }
                        val frames = size / (channels * 2)
                        if (frames >= rate.toLong() * maxSeconds) throw SpeechTooLong()
                        dataAt = body
                        dataSize = size
                    }
                }
                at = next
            }
            require(dataAt >= 0) { "diagnostic WAV has no PCM" }
            checkActive()
            val frames = (dataSize / (channels * 2)).toInt()
            val outputSize = frames.toLong() * OUTPUT_RATE / rate
            require(outputSize in 1 until OUTPUT_RATE.toLong() * maxSeconds) { "diagnostic resample out of bounds" }
            input.seek(dataAt)
            val bytes = ByteArray(dataSize.toInt())
            var read = 0
            while (read < bytes.size) {
                checkActive()
                val count = minOf(8192, bytes.size - read)
                input.readFully(bytes, read, count)
                read += count
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            checkActive()
            val mono = ShortArray(frames)
            for (i in mono.indices) {
                if (i and 1023 == 0) checkActive()
                var sum = 0
                repeat(channels) { sum += buffer.short }
                mono[i] = (sum / channels).toShort()
            }
            if (rate == OUTPUT_RATE) return@use mono.also { checkActive() }
            checkActive()
            val output = ShortArray(outputSize.toInt())
            for (i in output.indices) {
                if (i and 1023 == 0) checkActive()
                val position = i.toDouble() * rate / OUTPUT_RATE
                val j = position.toInt()
                val fraction = position - j
                val a = mono[minOf(j, mono.lastIndex)]
                val b = mono[minOf(j + 1, mono.lastIndex)]
                output[i] = (a + (b - a) * fraction).toInt().toShort()
            }
            checkActive()
            output
        }
    }
}

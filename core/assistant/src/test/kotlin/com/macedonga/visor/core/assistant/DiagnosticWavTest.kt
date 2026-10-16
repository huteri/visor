package com.macedonga.visor.core.assistant

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DiagnosticWavTest {
    private fun read(file: File, seconds: Int = 10, checkActive: () -> Unit = {}): ShortArray =
        DiagnosticWav.read(file, seconds, checkActive)

    private fun wav(rate: Int = 48_000, channels: Int = 1, frames: Int = 4): ByteArray {
        val data = frames * maxOf(channels, 1) * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(channels.toShort()); putInt(rate); putInt(rate * channels * 2)
            putShort((channels * 2).toShort()); putShort(16); put("data".toByteArray()); putInt(data)
            repeat(frames * maxOf(channels, 1)) { putShort(100) }
        }.array()
    }
    private fun withWav(bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("diagnostic-wav", ".wav")
        try { file.writeBytes(bytes); block(file) } finally { file.delete() }
    }
    private fun rejected(bytes: ByteArray, seconds: Int = 10) = withWav(bytes) { file ->
        try { read(file, seconds); fail("unsafe WAV accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `rejects unsupported tiny sample rate before resampling`() { rejected(wav(rate = 1)) }
    @Test fun `rejects zero channels`() { rejected(wav(channels = 0)) }
    @Test fun `rejects negative chunk sizes`() {
        rejected(wav().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1) })
    }
    @Test fun `bounds first second and delayed duration before resampling`() {
        rejected(wav(rate = 8_000, frames = 8_000 * 25), 25)
        rejected(wav(rate = 8_000, frames = 8_000 * 10), 10)
    }
    @Test fun `rejects huge file before reading its contents`() = withWav(wav()) { file ->
        RandomAccessFile(file, "rw").use { it.setLength(9L * 1024 * 1024) }
        try { read(file); fail("huge file accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun `accepts valid PCM and bounds resampled output`() = withWav(wav(rate = 8_000, channels = 2)) { file ->
        val samples = read(file)
        assertEquals(24, samples.size)
        assertTrue(samples.all { it == 100.toShort() })
    }
    @Test fun `rejects non PCM bit depth layout and unsupported channels`() {
        for ((offset, value) in listOf(20 to 3, 22 to 3, 32 to 1, 34 to 8)) {
            rejected(wav().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, value.toShort()) })
        }
    }
    @Test fun `rejects truncated chunk headers payloads and bad RIFF bounds`() {
        rejected(wav().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 100) })
        rejected(wav().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 15) })
        rejected(wav().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 1) })
        rejected(wav().copyOf(45).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 37) })
    }
    @Test fun `zero sized unknown chunk advances and valid padded chunk is skipped`() {
        for (size in listOf(0, 1)) {
            val original = wav()
            val extra = ByteBuffer.allocate(8 + size + (size and 1)).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("JUNK".toByteArray()); putInt(size)
            }.array()
            val bytes = original.copyOfRange(0, 12) + extra + original.copyOfRange(12, original.size)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bytes.size - 8)
            withWav(bytes) { assertEquals(4, read(it).size) }
        }
    }
    @Test fun `rejects non progressing signed chunk size without looping`() {
        rejected(wav().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16, -8) })
    }
    @Test fun `cancellation interrupts mixdown and resampling loops`() {
        for (stopAt in listOf(8, 13)) withWav(wav(rate = 8_000, frames = 4096)) { file ->
            var checks = 0
            try {
                read(file) { if (++checks == stopAt) throw java.util.concurrent.CancellationException("cancel") }
                fail("synchronous loop ignored cancellation")
            } catch (_: java.util.concurrent.CancellationException) { }
            assertEquals(stopAt, checks)
        }
    }
    @Test fun `cancellation is checked during synchronous work`() = withWav(wav(frames = 4096)) { file ->
        var checks = 0
        try {
            read(file) { if (++checks == 4) throw java.util.concurrent.CancellationException("cancel") }
            fail("cancellation ignored")
        } catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(4, checks)
    }
}

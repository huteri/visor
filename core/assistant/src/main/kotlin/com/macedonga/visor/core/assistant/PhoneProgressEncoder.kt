package com.macedonga.visor.core.assistant

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteOrder

/** Streaming platform Opus encoder. Feeds at most 20ms; never stores a turn's PCM. */
internal class PhoneProgressEncoder : ProgressEncoder {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private var fed = 0L
    private var closed = false
    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (error: Throwable) {
            try { codec.release() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }
    override fun push(pcm: ShortArray, end: Boolean): List<OpusPacket> {
        require(pcm.size <= 960)
        val packets = ArrayList<OpusPacket>()
        val info = MediaCodec.BufferInfo()
        val until = SystemClock.elapsedRealtime() + 1_000
        var submitted = false
        while (true) {
            check(SystemClock.elapsedRealtime() < until) { "Spoken progress: Opus encoder stalled" }
            if (!submitted) {
                val index = codec.dequeueInputBuffer(1_000)
                if (index >= 0) {
                    val buffer = checkNotNull(codec.getInputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                    buffer.clear()
                    check(buffer.remaining() >= pcm.size * 2) { "Spoken progress: codec input buffer too small" }
                    for (sample in pcm) buffer.putShort(sample)
                    codec.queueInputBuffer(index, 0, pcm.size * 2, fed * 1_000_000 / 48_000,
                        if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    fed += pcm.size
                    submitted = true
                }
            }
            val out = codec.dequeueOutputBuffer(info, if (end) 1_000 else 0)
            if (out >= 0) {
                try {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val buffer = checkNotNull(codec.getOutputBuffer(out))
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(data)
                        packets += OpusPacket(data, opusDurationUs(data))
                    }
                } finally { codec.releaseOutputBuffer(out, false) }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return packets
            } else if (submitted && !end && out == MediaCodec.INFO_TRY_AGAIN_LATER) return packets
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try { codec.stop() } catch (error: Throwable) { failure = error }
        try { codec.release() } catch (error: Throwable) {
            if (failure != null) failure.addSuppressed(error) else failure = error
        }
        failure?.let { throw it }
    }
}

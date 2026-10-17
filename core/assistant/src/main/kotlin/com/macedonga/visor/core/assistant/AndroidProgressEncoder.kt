package com.macedonga.visor.core.assistant

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Only used on the worker dispatcher, never the ordinary batch/diagnostic encoders. */
internal class AndroidProgressEncoder : ProgressEncoder {
    private val pump = ProgressEncoderPump(AndroidOpusCodec(), SystemClock::elapsedRealtime, { delay(it) })
    override suspend fun encode(pcm: ShortArray, end: Boolean): List<OpusPacket> = withContext(Dispatchers.Default) {
        pump.encode(pcm, end)
    }
    override fun close() = pump.close()
}

private class AndroidOpusCodec : ProgressCodec {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (error: Throwable) {
            codec.release()
            throw error
        }
    }
    override fun offer(pcm: ShortArray, ptsUs: Long, end: Boolean): Boolean {
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return false
        val buffer = checkNotNull(codec.getInputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
        buffer.clear()
        check(buffer.remaining() >= pcm.size * 2) { "Opus input buffer too small" }
        for (sample in pcm) buffer.putShort(sample)
        codec.queueInputBuffer(index, 0, pcm.size * 2, ptsUs, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
        return true
    }
    override fun poll(): CodecOutput? {
        val index = codec.dequeueOutputBuffer(info, 0)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) return CodecOutput(null, false)
        if (index < 0) return null
        try {
            val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || info.size == 0) return CodecOutput(null, end)
            require(info.size in 1..65_536) { "Opus packet too large" }
            val buffer = checkNotNull(codec.getOutputBuffer(index))
            buffer.position(info.offset)
            val bytes = ByteArray(info.size)
            buffer.get(bytes)
            return CodecOutput(OpusPacket(bytes, opusDurationUs(bytes)), end)
        } finally { codec.releaseOutputBuffer(index, false) }
    }
    private var closed = false
    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        codec.release()
    }
}

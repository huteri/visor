package com.macedonga.visor.core.assistant

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One Opus packet and how long it plays. */
class OpusPacket(val data: ByteArray, val durationUs: Long)

/**
 * Text to what the glasses play: the phone's own TextToSpeech, resampled to 48 kHz mono, encoded
 * to raw Opus packets by the platform encoder.
 */
class Voice(private val context: Context) {

    private val lock = Mutex()
    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** The engine and its default voice, for the screen. */
    var engine: String = ""
        private set

    suspend fun speak(text: String, locale: Locale): List<OpusPacket> = lock.withLock {
        val wav = File(context.cacheDir, "assistant-tts.wav")
        synthesize(text, locale, wav)
        val pcm = withContext(Dispatchers.Default) { readWav(wav) }
        withContext(Dispatchers.Default) { encodeOpus(resample(pcm.samples, pcm.rate, 48_000)) }
    }

    /** Bounded PCM for a continuous response; encoding belongs to the turn, not the clip. */
    internal suspend fun progressPcm(text: String, locale: Locale): ShortArray = lock.withLock {
        require(text.length <= 240) { "Spoken progress: TTS segment exceeds 240 characters" }
        val wav = File(context.cacheDir, "assistant-progress-tts.wav")
        try {
            synthesize(text, locale, wav)
            check(wav.length() <= 4_000_000) { "Spoken progress: WAV exceeds 4 MB" }
            withContext(Dispatchers.Default) {
                val pcm = readWav(wav)
                check(pcm.samples.size.toLong() <= pcm.rate.toLong() * 20) { "Spoken progress: TTS exceeds 20 seconds" }
                resample(pcm.samples, pcm.rate, 48_000)
            }
        } finally { wav.delete() }
    }

    internal fun progressEncoder(): ProgressEncoder = PhoneProgressEncoder()

    private suspend fun synthesize(text: String, locale: Locale, out: File) {
        val engine = ready()
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>().also { pending[id] = it }
        out.delete()
        try {
            check(engine.synthesizeToFile(text, Bundle(), out, id) == TextToSpeech.SUCCESS) { "the phone's text to speech refused the sentence" }
            val ok = withTimeout(15_000) { done.await() }
            check(ok && out.length() > 44) { "the phone's text to speech failed" }
        } finally {
            pending.remove(id)
            if (!done.isCompleted) engine.stop()
        }
    }

    private suspend fun ready(): TextToSpeech {
        tts?.let { return it }
        var made: TextToSpeech? = null
        val status = suspendCancellableCoroutine { cont ->
            made = TextToSpeech(context) { if (cont.isActive) cont.resume(it) }
            cont.invokeOnCancellation { made?.shutdown() }
        }
        val engine = checkNotNull(made)
        check(status == TextToSpeech.SUCCESS) { "the phone has no working text to speech" }
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) {
                    pending[utteranceId]?.complete(true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    pending[utteranceId]?.complete(false)
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    pending[utteranceId]?.complete(false)
                }
            },
        )
        this.engine = engine.defaultEngine.orEmpty()
        tts = engine
        return engine
    }

    fun close() {
        tts?.shutdown()
        tts = null
    }

    private class Pcm(val samples: ShortArray, val rate: Int)

    /** 16-bit PCM WAV, mixed down to mono. Some engines leave the data size unset, so it is clamped. */
    private fun readWav(file: File): Pcm {
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(bytes.size > 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        var at = 12
        var rate = 0
        var channels = 1
        var bits = 16
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4)
            val size = b.getInt(at + 4)
            val body = at + 8
            if (id == "fmt ") {
                check(body + 16 <= bytes.size) { "truncated WAV format" }
                channels = b.getShort(body + 2).toInt()
                rate = b.getInt(body + 4)
                bits = b.getShort(body + 14).toInt()
            } else if (id == "data") {
                check(bits == 16 && rate in 8_000..192_000 && channels in 1..8) { "the speech is $bits-bit at $rate Hz" }
                val end = if (size <= 0 || body + size > bytes.size) bytes.size else body + size
                val frames = (end - body) / (2 * channels)
                val out = ShortArray(frames)
                for (i in 0 until frames) {
                    var sum = 0
                    for (c in 0 until channels) sum += b.getShort(body + (i * channels + c) * 2)
                    out[i] = (sum / channels).toShort()
                }
                return Pcm(out, rate)
            }
            check(size >= 0 && size.toLong() + body <= bytes.size) { "invalid WAV chunk" }
            at = body + size + (size and 1)
        }
        error("the WAV has no data")
    }

    private fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to) return input
        val n = (input.size.toLong() * to / from).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val pos = i.toDouble() * from / to
            val j = pos.toInt()
            val frac = pos - j
            val a = input[minOf(j, input.size - 1)]
            val c = input[minOf(j + 1, input.size - 1)]
            out[i] = (a + (c - a) * frac).toInt().toShort()
        }
        return out
    }

    /** The platform Opus encoder, one packet per output buffer, codec config left out. */
    private fun encodeOpus(pcm: ShortArray): List<OpusPacket> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val packets = ArrayList<OpusPacket>()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { bytes.putShort(it) }
            val raw = bytes.array()
            var fed = 0
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(index))
                        val take = minOf(buffer.capacity(), raw.size - fed) and 1.inv()
                        val pts = fed / 2 * 1_000_000L / 48_000
                        if (take <= 0) {
                            codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.clear()
                            buffer.put(raw, fed, take)
                            codec.queueInputBuffer(index, 0, take, pts, 0)
                            fed += take
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out >= 0) {
                    val buffer = checkNotNull(codec.getOutputBuffer(out))
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(data)
                        packets += OpusPacket(data, opusDurationUs(data))
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return packets
    }
}

/** How long an Opus packet plays, from its TOC byte (RFC 6716, 3.1). */
internal fun opusDurationUs(packet: ByteArray): Long {
    if (packet.isEmpty()) return 0
    val toc = packet[0].toInt() and 0xFF
    val config = toc shr 3
    val frameUs = when {
        config < 12 -> longArrayOf(10_000, 20_000, 40_000, 60_000)[config and 3]
        config < 16 -> longArrayOf(10_000, 20_000)[config and 1]
        else -> longArrayOf(2_500, 5_000, 10_000, 20_000)[config and 3]
    }
    val frames = when (toc and 3) {
        0 -> 1
        1, 2 -> 2
        else -> if (packet.size > 1) packet[1].toInt() and 0x3F else 1
    }
    return frameUs * frames
}

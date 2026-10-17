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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive

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

    /** No encoded clips: the caller owns the one live encoder, outside this synthesis lock. */
    internal suspend fun speechPcm(text: String, locale: Locale): ShortArray = lock.withLock {
        val wav = File(context.cacheDir, "progress-${UUID.randomUUID()}.wav")
        try {
            synthesize(text, locale, wav, diagnostic = true)
            withContext(Dispatchers.Default) {
                val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                DiagnosticWav.readBoundedPcm(wav, 25) { job?.ensureActive() }
            }
        } finally { wav.delete() }
    }

    /** Diagnostic only: phone TTS, one 48 kHz PCM timeline, one existing Opus encoder instance. */
    internal suspend fun diagnosticSpeech(first: String, second: String?, locale: Locale): List<OpusPacket> = lock.withLock {
        val firstWav = File(context.cacheDir, "diagnostic-${UUID.randomUUID()}.wav")
        val secondWav = File(context.cacheDir, "diagnostic-${UUID.randomUUID()}.wav")
        try {
            synthesize(first, locale, firstWav, diagnostic = true)
            val a = withContext(Dispatchers.Default) {
                val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                DiagnosticWav.read(firstWav, if (second == null) 10 else 25) { job?.ensureActive() }
            }
            val pcm = if (second == null) a else {
                synthesize(second, locale, secondWav, diagnostic = true)
                val b = withContext(Dispatchers.Default) {
                    val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                    DiagnosticWav.read(secondWav, 10) { job?.ensureActive() }
                }
                diagnosticPcm(a, b)
            }
            withContext(Dispatchers.Default) {
                val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                encodeOpus(pcm) { job?.ensureActive() }
            }
        } finally {
            firstWav.delete()
            secondWav.delete()
        }
    }

    private suspend fun synthesize(text: String, locale: Locale, out: File, diagnostic: Boolean = false) {
        val engine = ready()
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>().also { pending[id] = it }
        out.delete()
        try {
            check(engine.synthesizeToFile(text, Bundle(), out, id) == TextToSpeech.SUCCESS) { "the phone's text to speech refused the sentence" }
            val ok = withTimeout(15_000) { done.await() }
            pending.remove(id)
            check(ok && out.length() > 44) { "the phone's text to speech failed" }
        } finally {
            if (diagnostic) {
                pending.remove(id)
                if (!done.isCompleted) engine.stop()
            }
        }
    }

    private suspend fun ready(): TextToSpeech {
        tts?.let { return it }
        val initialized = CompletableDeferred<Int>()
        val engine = TextToSpeech(context) { initialized.complete(it) }
        try {
            val status = withTimeout(15_000) { initialized.await() }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            check(status == TextToSpeech.SUCCESS) { "the phone has no working text to speech" }
        } catch (error: Throwable) {
            engine.shutdown()
            throw error
        }
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
                channels = b.getShort(body + 2).toInt()
                rate = b.getInt(body + 4)
                bits = b.getShort(body + 14).toInt()
            } else if (id == "data") {
                check(bits == 16 && rate > 0) { "the speech is $bits-bit at $rate Hz" }
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
    private fun encodeOpus(pcm: ShortArray, checkActive: () -> Unit = {}): List<OpusPacket> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val packets = ArrayList<OpusPacket>()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            checkActive()
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEachIndexed { index, sample ->
                if (index and 1023 == 0) checkActive()
                bytes.putShort(sample)
            }
            val raw = bytes.array()
            var fed = 0
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            while (true) {
                checkActive()
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

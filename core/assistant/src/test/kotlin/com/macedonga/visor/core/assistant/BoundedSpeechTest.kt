package com.macedonga.visor.core.assistant

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BoundedSpeechTest {
    @Test fun `hard text boundaries preserve non BMP characters and text order`() = runBlocking {
        for (text in listOf("x".repeat(159) + "😀" + "y".repeat(190), "漢字".repeat(200))) {
            val clips = mutableListOf<String>()
            val speech = BoundedSpeech(text) { clip -> clips += clip; shortArrayOf(1) }
            while (speech.hasNext()) speech.next()
            assertEquals(text, clips.joinToString(""))
            assertTrue(clips.all { it.length <= 160 })
            assertTrue(clips.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
        }
    }

    @Test fun `malformed container is not treated as a retryable duration failure`() = runBlocking {
        val failure = IllegalArgumentException("invalid diagnostic RIFF")
        var attempts = 0
        val speech = BoundedSpeech("hello") { attempts++; throw failure }
        try { speech.next(); fail("invalid WAV must propagate") }
        catch (expected: IllegalArgumentException) { assertSame(failure, expected) }
        assertEquals(1, attempts)
    }

    @Test fun `unrepresentably slow single codepoint fails finitely without dropping it`() = runBlocking {
        for (text in listOf("A", "😀")) {
            var attempts = 0
            val speech = BoundedSpeech(text) { attempts++; throw DiagnosticWav.SpeechTooLong() }
            try { speech.next(); fail("cannot split a codepoint") }
            catch (_: DiagnosticWav.SpeechTooLong) { }
            assertEquals(1, attempts)
            assertTrue(speech.hasNext())
        }
    }

    @Test fun `cancellation never becomes a duration retry`() = runBlocking {
        var attempts = 0
        val cancellation = kotlinx.coroutines.CancellationException("stop")
        val speech = BoundedSpeech("hello") { attempts++; throw cancellation }
        try { speech.next(); fail("cancellation must propagate") }
        catch (expected: kotlinx.coroutines.CancellationException) { assertSame(cancellation, expected) }
        assertEquals(1, attempts)
    }

    @Test fun `slow engine clips over duration limit are retried smaller without losing text`() = runBlocking {
        val text = "A slow voice can exceed the duration limit even for a small piece of the final answer."
        val accepted = mutableListOf<String>()
        var attempts = 0
        val file = File.createTempFile("speech-duration", ".wav")
        try {
            // Real strict reader rejects duration before allocating PCM.
            val frames = 8_000 * 25
            val bytes = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + frames * 2); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(frames * 2)
            }.array()
            file.writeBytes(bytes)
            val speech = BoundedSpeech(text) { clip ->
                attempts++
                if (clip.length > 40) DiagnosticWav.readBoundedPcm(file, 25)
                else { accepted += clip; ShortArray(960) { 1 } }
            }
            while (speech.hasNext()) assertEquals(960, speech.next().size)
            assertEquals(text, accepted.joinToString(""))
            assertTrue(attempts > accepted.size)
        } finally { file.delete() }
    }
}

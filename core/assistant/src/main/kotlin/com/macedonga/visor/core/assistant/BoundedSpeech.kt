package com.macedonga.visor.core.assistant

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Lazy text slicing: never synthesize a whole answer or retain a queue of PCM clips. */
internal class BoundedSpeech(
    private val text: String,
    private val synthesize: suspend (String) -> ShortArray,
) {
    private var at = 0
    fun hasNext(): Boolean = at < text.length

    suspend fun next(): ShortArray {
        check(hasNext()) { "no speech remaining" }
        var end = boundary(minOf(at + MAX_CHARS, text.length))
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val pcm = synthesize(text.substring(at, end))
                if (pcm.size >= 48_000 * 25) throw DiagnosticWav.SpeechTooLong()
                require(pcm.isNotEmpty()) { "speech PCM out of bounds" }
                at = end
                return pcm
            } catch (tooLong: DiagnosticWav.SpeechTooLong) {
                // A character limit is not a duration guarantee for every engine/voice.
                val smaller = boundary(at + (end - at) / 2)
                if (smaller <= at || smaller >= end) throw tooLong
                end = smaller
            }
        }
    }

    private fun boundary(limit: Int): Int {
        var end = limit
        if (end < text.length) {
            if (end > at && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            val space = (end - 1 downTo at + 1).firstOrNull { text[it].isWhitespace() }
            if (space != null) end = space + 1
        }
        return end
    }

    companion object { const val MAX_CHARS = 160 }
}

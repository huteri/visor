package com.macedonga.visor.core.assistant

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Explicit opt-in only. No svc53 photo capture, disk writes, event-log output or network requests. */
internal class VisualDiagnostics(
    private val onRevoke: (String) -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Export(val generation: Long, val count: Int, val dropped: Int)
    private val exports = linkedMapOf<String, Export>()
    private var generation = 0L
    private data class Entry(val type: Int, val channel: Int, val at: Long, val bytes: ByteArray)
    private val entries = mutableListOf<Entry>()
    private var until = 0L
    private var dropped = 0

    @Synchronized fun start() {
        clear()
        until = now() + 120_000
    }

    @Synchronized fun record(type: Int, bytes: ByteArray, channel: Int = 0) {
        if (until == 0L || now() >= until || type !in setOf(65, 1002, 1003)) return
        // Include framing in the cap. Never truncate an individual request and call it complete.
        if (bytes.size > 512 * 1024 || entries.size >= 256 ||
            32L + entries.sumOf { 20L + it.bytes.size } + 20L + bytes.size > 2 * 1024 * 1024) {
            if (dropped < Int.MAX_VALUE) dropped++
            return
        }
        entries.add(Entry(type, channel, now(), bytes.copyOf()))
    }

    @Synchronized fun clear() {
        entries.clear()
        until = 0L
        dropped = 0
        generation++
        val revoked = exports.keys.toList()
        exports.clear() // Denial is effective even if platform revocation fails.
        revoked.forEach(onRevoke)
    }

    @Synchronized fun expire() {
        if (until != 0L && now() >= until + 480_000) clear()
    }

    /** Tokens retain only a bounded prefix descriptor, never another copy of sensitive bytes. */
    @Synchronized fun publish(): String? {
        expire()
        if (entries.isEmpty() && dropped == 0) return null
        if (exports.size >= 32) {
            val oldest = exports.keys.first()
            exports.remove(oldest)
            onRevoke(oldest)
        }
        val token = java.util.UUID.randomUUID().toString()
        exports[token] = Export(generation, entries.size, dropped)
        return token
    }

    @Synchronized fun read(token: String): ByteArray? {
        expire()
        val export = exports[token]?.takeIf { it.generation == generation } ?: return null
        return encode(export.count, export.dropped)
    }

    /** UTF magic, int count, int dropped, then (int kind, int channel, long monotonic ms, int length, bytes). */
    @Synchronized fun export(): ByteArray? {
        expire()
        if (entries.isEmpty() && dropped == 0) return null
        return encode(entries.size, dropped)
    }

    private fun encode(count: Int, dropped: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeUTF("VISOR-VISUAL-1")
            data.writeInt(count)
            data.writeInt(dropped)
            entries.take(count).forEach { entry ->
                data.writeInt(entry.type)
                data.writeInt(entry.channel)
                data.writeLong(entry.at)
                data.writeInt(entry.bytes.size)
                data.write(entry.bytes)
            }
        }
        return out.toByteArray()
    }
}

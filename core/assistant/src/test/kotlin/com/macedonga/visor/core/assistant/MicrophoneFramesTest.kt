package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test

class MicrophoneFramesTest {
    @Test fun privateCapturedLiveStreamExtractsCodecVerifiedPackets() {
        // Private scratch replay only: never commit captured microphone audio.
        val path = System.getenv("VISOR_MIC_REPLAY")
        org.junit.Assume.assumeNotNull(path)
        java.io.File(path!!).readLines().forEachIndexed { index, line ->
            val parts = line.split('|')
            val actual = mutableListOf<Pair<String, Long?>>()
            assertTrue("capture $index", readMicrophoneFrames(hex(parts[2])) { p, ms ->
                actual += p.joinToString("") { "%02x".format(it) } to ms
            })
            val expected = if (parts[1].isEmpty()) emptyList() else listOf(parts[1] to parts[0].toLong())
            assertEquals("capture $index", expected, actual)
        }
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun capturedEmptyLiveRecordsAreConsumedWithoutDecoding() {
        // Header/control-only bytes; no captured audio.
        val captures = listOf("090000007ceb01000100000000", "090000007ceb01000200000000",
            "0900000084eb01000100000000", "09000000d1a808000100000000",
            "09000000d1a808000200000000", "09000000d9a808000100000000",
            "090000008ef30b000100000000", "090000008ef30b000200000000",
            "0900000096f30b000100000000")
        for (hex in captures) {
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            var calls = 0
            assertTrue("empty live record is valid control, not audio", readMicrophoneFrames(bytes) { _, _ -> calls++ })
            assertEquals(0, calls)
        }
    }

    // Synthetic codec bytes only. Wire shape measured in private capture; no wearer audio retained.
    private fun live(packet: ByteArray, ms: Int = 123, selector: Int = 1): ByteArray {
        val payload = packet.size + 8
        val size = if (payload < 128) byteArrayOf(payload.toByte())
            else byteArrayOf(((payload and 127) or 128).toByte(), (payload shr 7).toByte())
        return java.nio.ByteBuffer.allocate(12 + size.size + payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(8 + size.size + payload).putInt(ms).putInt(selector).put(size)
            .order(java.nio.ByteOrder.BIG_ENDIAN).putInt(packet.size).putInt(0x12345678).put(packet).array()
    }

    @Test fun syntheticNestedLiveFramingKeepsOnlyPacketAndUnsignedClock() {
        val packet = byteArrayOf(0x48, 0)
        val actual = mutableListOf<Pair<List<Byte>, Long?>>()
        val chunk = live(packet, -1) + hex("09000000000000000200000000") + live(packet, 19, 2)
        assertTrue(readMicrophoneFrames(chunk) { p, ms -> actual += p.toList() to ms })
        assertEquals(listOf(packet.toList() to 0xFFFFFFFFL, packet.toList() to 19L), actual)
    }

    @Test fun multibyteUnsignedPayloadLengthIsNotFixedLE32() {
        val packet = ByteArray(128) { 0x48 }
        assertTrue(readMicrophoneFrames(live(packet)) { p, ms ->
            assertArrayEquals(packet, p)
            assertEquals(123L, ms)
        })
    }

    @Test fun malformedLengthsAndTruncationsNeverDeliverPartialAudio() {
        val good = live(byteArrayOf(0x48, 0))
        val malformed = mutableListOf<ByteArray>()
        for (size in 0 until good.size) malformed += good.copyOf(size)
        for (at in listOf(0, 12, 16)) {
            malformed += good.copyOf().also { it[at] = (it[at] + 1).toByte() }
        }
        malformed += good.copyOf().also { java.util.Arrays.fill(it, 0, 4, 0x7F.toByte()) }
        malformed += hex("0d00000000000000010000008080808080")
        malformed += hex("09000000000000000300000000")
        malformed += hex("09000000000000000100000001")
        for ((i, bad) in malformed.withIndex()) {
            var calls = 0
            assertFalse("malformed $i", readMicrophoneFrames(bad) { _, _ -> calls++ })
            assertEquals("malformed $i callback", 0, calls)
            if (bad.isNotEmpty()) {
                assertFalse("suffix $i", readMicrophoneFrames(good + bad) { _, _ -> calls++ })
                assertEquals("suffix $i callback", 0, calls)
            }
        }
        var calls = 0
        assertFalse(readMicrophoneFrames(hex("00000002480000000003")) { _, _ -> calls++ })
        assertEquals(0, calls)
    }

    @Test fun shutdownTimedRecordRetainsZeroClockAndStripsRange() {
        val shutdown = hex("0e0000000000000000000002123456784800")
        var calls = 0
        assertTrue(readMicrophoneFrames(shutdown) { p, ms ->
            calls++
            assertArrayEquals(hex("4800"), p)
            assertEquals(0L, ms)
        })
        assertEquals(1, calls)
    }

    @Test fun knownQuestionFramingKeepsPacketBytes() {
        val packets = mutableListOf<ByteArray>()
        assertTrue(readMicrophoneFrames(byteArrayOf(0, 0, 0, 2, 0x78, 0)) { p, ms ->
            assertNull(ms)
            packets += p
        })
        assertArrayEquals(byteArrayOf(0x78, 0), packets.single())
    }

    @Test fun knownFollowupFramingKeepsClockAndPacketBytes() {
        val chunk = java.nio.ByteBuffer.allocate(18).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(14).putInt(123).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(2).putInt(0)
            .put(byteArrayOf(0x78, 0)).array()
        var calls = 0
        assertTrue(readMicrophoneFrames(chunk) { p, ms ->
            calls++
            assertEquals(123L, ms)
            assertArrayEquals(byteArrayOf(0x78, 0), p)
        })
        assertEquals(1, calls)
    }
}

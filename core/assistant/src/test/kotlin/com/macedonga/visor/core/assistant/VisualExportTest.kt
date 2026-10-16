package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.Collections

class VisualExportTest {
    @Test fun `each export is isolated from later captures and process recreation`() {
        val capture = VisualDiagnostics()
        capture.start()
        capture.record(65, byteArrayOf(1))
        val first = checkNotNull(capture.publish())
        val original = checkNotNull(capture.read(first))
        capture.record(1002, byteArrayOf(2))
        val second = checkNotNull(capture.publish())
        assertNotEquals(first, second)
        assertArrayEquals(original, capture.read(first))
        assertFalse(original.contentEquals(capture.read(second)))
        assertNull(VisualDiagnostics().read(first))
    }

    @Test fun `delete restart and expiry invalidate tokens and explicitly revoke them`() {
        var time = 100L
        val revoked = mutableListOf<String>()
        val capture = VisualDiagnostics(onRevoke = { revoked.add(it) }, now = { time })
        fun token(): String {
            capture.record(65, byteArrayOf(1))
            return checkNotNull(capture.publish())
        }
        capture.start()
        val deleted = token()
        capture.clear()
        assertNull(capture.read(deleted))
        capture.start()
        val restarted = token()
        capture.start()
        assertNull(capture.read(restarted))
        val expired = token()
        time += 600_000
        assertNull(capture.read(expired))
        assertNull(capture.publish())
        assertEquals(listOf(deleted, restarted, expired), revoked)
    }

    @Test fun `sleep spanning elapsed jumps enforce deadlines without scheduled expiry`() {
        for (readFirst in listOf(true, false)) {
            var elapsed = 100L
            val revoked = mutableListOf<String>()
            val capture = VisualDiagnostics(now = { elapsed }, onRevoke = { revoked.add(it) })
            capture.start()
            capture.record(65, byteArrayOf(1))
            val token = checkNotNull(capture.publish())
            val beforeSleep = checkNotNull(capture.read(token))

            // Simulate deep sleep: elapsed time advances, no coroutine or expire() runs.
            elapsed += 120_000
            capture.record(1003, byteArrayOf(2))
            val afterSleep = checkNotNull(capture.publish())
            assertArrayEquals(beforeSleep, capture.read(afterSleep))

            elapsed += 480_000
            capture.record(65, byteArrayOf(3))
            if (readFirst) assertNull(capture.read(token)) else assertNull(capture.publish())
            assertNull(capture.read(token))
            assertNull(capture.read(afterSleep))
            assertNull(capture.publish())
            assertNull(capture.export())
            assertEquals(listOf(token, afterSleep), revoked)
        }
    }

    @Test fun `publication racing deletion cannot resurrect a snapshot`() {
        repeat(100) {
            val capture = VisualDiagnostics()
            capture.start()
            capture.record(65, byteArrayOf(1))
            val go = CountDownLatch(1)
            val tokens = Collections.synchronizedList(mutableListOf<String>())
            val publisher = Thread { go.await(); repeat(20) { capture.publish()?.let { tokens.add(it) } } }
            publisher.start()
            go.countDown()
            capture.clear()
            publisher.join()
            tokens.forEach { assertNull(capture.read(it)) }
            assertNull(capture.publish())
        }
    }
}

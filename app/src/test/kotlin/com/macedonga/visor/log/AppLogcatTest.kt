package com.macedonga.visor.log

import org.junit.Assert.*
import org.junit.Test

class AppLogcatTest {
    @Test fun captureReturnsOnlyProcessOutputAndDestroysProcess() {
        val process = FakeProcess("own app line\n")
        assertEquals("own app line\n", AppLogcat.capture(12345, { process }))
        assertTrue(process.destroyed)
    }

    @Test fun blockedReadAlsoTimesOutAndClosesStream() {
        val input = object : java.io.InputStream() {
            @Volatile var closed = false
            override fun read(): Int { while (!closed) Thread.sleep(5); return -1 }
            override fun close() { closed = true }
        }
        val process = object : Process() {
            var destroyed = false
            override fun getInputStream() = input
            override fun getErrorStream() = "".byteInputStream()
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() { destroyed = true }
        }
        expectFailure { AppLogcat.capture(12345, { process }, timeoutMillis = 30) }
        assertTrue(process.destroyed)
        assertTrue(input.closed)
    }

    @Test fun startFailurePropagatesWithoutFallback() {
        var attempts = 0
        expectFailure {
            AppLogcat.capture(12345, { command ->
                attempts++
                assertTrue(command.contains("--uid=12345"))
                throw java.io.IOException("unavailable")
            })
        }
        assertEquals(1, attempts)
    }

    @Test fun emptyOutputIsNotSuccess() {
        expectFailure { AppLogcat.capture(12345, { FakeProcess("--------- beginning of main\n") }) }
    }

    @Test fun failingCommandDoesNotShareErrorOutput() {
        expectFailure { AppLogcat.capture(12345, { FakeProcess("unknown option --uid", 1) }) }
    }

    @Test fun outputHasHardByteBound() {
        val process = FakeProcess("x".repeat(101))
        expectFailure { AppLogcat.capture(12345, { process }, maxBytes = 100) }
        assertTrue(process.destroyed)
    }

    @Test fun timeoutDestroysProcess() {
        val process = FakeProcess("", stall = true)
        expectFailure { AppLogcat.capture(12345, { process }, timeoutMillis = 30) }
        assertTrue(process.destroyed)
    }

    @Test fun interruptionDestroysProcess() {
        val process = FakeProcess("", stall = true)
        val worker = Thread { expectFailure { AppLogcat.capture(12345, { process }) } }
        worker.start()
        Thread.sleep(30)
        worker.interrupt()
        worker.join(1000)
        assertFalse(worker.isAlive)
        assertTrue(process.destroyed)
    }

    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected capture failure") } catch (_: Exception) { }
    }

    private class FakeProcess(private val text: String, private val code: Int = 0, private val stall: Boolean = false) : Process() {
        @Volatile var destroyed = false
        override fun getInputStream() = text.byteInputStream()
        override fun getErrorStream() = "".byteInputStream()
        override fun getOutputStream() = java.io.ByteArrayOutputStream()
        override fun waitFor(): Int {
            if (stall) Thread.sleep(60_000)
            return code
        }
        override fun exitValue() = code
        override fun destroy() { destroyed = true }
    }

    @Test fun eventLogHasSeparateExplicitAppLogcatSharePath() {
        val screen = java.io.File("src/main/kotlin/com/macedonga/visor/log/EventLogScreen.kt").readText()
        assertTrue(screen.contains("R.string.event_log_share_logcat"))
        assertTrue(screen.contains("AppLogcat.capture(android.os.Process.myUid())"))
        val sharePath = screen.substringAfter("private suspend fun shareAppLogcat")
        assertTrue(sharePath.contains("runInterruptible(Dispatchers.IO)"))
        assertTrue(sharePath.contains("File.createTempFile"))
        assertFalse(sharePath.contains("report(context"))
        assertFalse(sharePath.contains("MetaLogin"))
    }

    @Test fun commandOnlyRequestsOwnUidAndLastThousandLines() {
        assertEquals(listOf("logcat", "-d", "-t", "1000", "-v", "threadtime", "--uid=12345"), AppLogcat.command(12345))
    }
}

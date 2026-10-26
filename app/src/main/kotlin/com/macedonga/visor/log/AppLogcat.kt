package com.macedonga.visor.log

/** App UID only, including earlier processes. Never fall back to unfiltered logs. */
internal object AppLogcat {
    const val MAX_BYTES = 512 * 1024

    /** Blocking seam; caller uses runInterruptible(IO). Deadline covers both reads and exit. */
    fun capture(
        uid: Int,
        start: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
        maxBytes: Int = MAX_BYTES,
        timeoutMillis: Long = 5_000,
    ): String {
        require(maxBytes in 1..MAX_BYTES && timeoutMillis in 1..5_000)
        val process = start(command(uid))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "visor-logcat-reader").apply { isDaemon = true }
        }
        val result = executor.submit<String> {
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            process.inputStream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(bytes.size() + count <= maxBytes) { "Logcat output exceeds size limit" }
                    bytes.write(buffer, 0, count)
                }
            }
            check(process.waitFor() == 0) { "Logcat command failed (UID filtering may be unsupported)" }
            val text = bytes.toString(Charsets.UTF_8.name())
            check(text.lineSequence().any { it.isNotBlank() && !it.startsWith("--------- beginning of") }) {
                "No app-accessible logcat lines available"
            }
            text
        }
        try {
            return result.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        } finally {
            process.destroy()
            result.cancel(true)
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            executor.shutdownNow()
        }
    }

    fun command(uid: Int): List<String> {
        require(uid >= 0)
        return listOf("logcat", "-d", "-t", "1000", "-v", "threadtime", "--uid=$uid")
    }
}

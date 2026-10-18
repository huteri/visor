package com.macedonga.visor.core.assistant.mcp

import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class OAuthLoopbackTest {
    @Test fun `loopback returns browser to the requesting debug package`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val redirect = "com.macedonga.visor.debug:/oauth"
        withTimeout(5_000) {
            val waiting = async(Dispatchers.IO) { awaitLoopback(port, redirect) }
            var page: String? = null
            for (attempt in 0 until 50) {
                try {
                    val http = URL("http://127.0.0.1:$port/callback?state=synthetic&code=synthetic").openConnection() as HttpURLConnection
                    try {
                        http.connectTimeout = 100
                        http.readTimeout = 1_000
                        page = http.inputStream.bufferedReader().use { it.readText() }
                    } finally { http.disconnect() }
                    break
                } catch (_: java.net.ConnectException) { delay(20) }
            }
            val html = checkNotNull(page)
            assertTrue(html.contains("href='$redirect'"))
            assertTrue(html.contains("location.href='$redirect'"))
            assertFalse(html.contains("com.macedonga.visor:/oauth"))
            assertEquals("http://127.0.0.1:$port/callback?state=synthetic&code=synthetic", waiting.await())
        }
    }
}

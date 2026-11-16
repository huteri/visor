package com.macedonga.visor.core.assistant

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrainRunsTest {
    @Test fun failedDirectorySyncSendsNoPostAndRetainsAdmissions() = runBlocking {
        scenario { base, diary, posts ->
            val dir = diary.photos.parentFile!!
            diary.keepSession("runs:existing.run", "existing admission")
            var syncs = 0
            val failing = Diary(dir, syncSessionDirectory = { parent ->
                assertEquals(dir, parent)
                // Rename already landed; do not roll back or erase uncertain admission.
                assertTrue(java.io.File(parent, "sessions.json").readText().contains("runs:new.run"))
                assertFalse(java.io.File(parent, "sessions.json.tmp").exists())
                syncs++
                throw java.io.IOException("directory sync failed")
            })
            val failure = runCatching { withContext(ConversationContext(failing, "c")) {
                HermesRuns.answer(base, "key", JSONObject().put("input", "q"), coroutineContext[ConversationContext], "runs:new")
            } }.exceptionOrNull()
            assertEquals("directory sync failed", failure?.message)
            assertEquals(1, syncs)
            assertEquals(0, posts.get())
            val restored = Diary(dir)
            assertEquals("existing admission", restored.session("runs:existing.run"))
            assertNotNull(restored.session("runs:new.run"))
        }
    }

    @Test fun failedAdmissionReplacementSendsNoPostAndRetainsExistingAdmission() = runBlocking {
        scenario { base, diary, posts ->
            val dir = diary.photos.parentFile!!
            val sessions = java.io.File(dir, "sessions.json")
            diary.keepSession("runs:existing.run", "existing admission")
            val original = sessions.readText()
            // Allow staging bytes, deny only replacement in the parent directory.
            java.io.File(dir, "sessions.json.tmp").writeText("")
            assertTrue(dir.setWritable(false, false))
            try {
                assertTrue(runCatching { withContext(ConversationContext(diary, "c")) {
                    HermesRuns.answer(base, "key", JSONObject().put("input", "q"), coroutineContext[ConversationContext], "runs:new")
                } }.isFailure)
                assertEquals(0, posts.get())
                assertEquals(original, sessions.readText())
                assertEquals("existing admission", Diary(dir).session("runs:existing.run"))
            } finally { assertTrue(dir.setWritable(true, true)) }
        }
    }

    @Test fun corruptSessionsFailClosedWithoutOverwrite() = damagedSessions(false)
    @Test fun unreadableSessionsFailClosedWithoutOverwrite() = damagedSessions(true)

    private fun damagedSessions(unreadable: Boolean) = runBlocking {
        scenario { base, diary, posts ->
            val sessions = java.io.File(diary.photos.parentFile, "sessions.json")
            diary.keepSession("runs:existing.run", "existing admission")
            if (!unreadable) sessions.writeText("""{"runs:existing.run":"existing admission",broken""")
            val original = sessions.readText()
            if (unreadable) assertTrue(sessions.setReadable(false, false))
            try {
                val read = runCatching { diary.session("runs:new.run") }
                val write = runCatching { diary.keepSession("runs:new.run", "new") }
                val answer = runCatching { withContext(ConversationContext(diary, "c")) {
                    HermesRuns.answer(base, "key", JSONObject().put("input", "q"), coroutineContext[ConversationContext], "runs:new")
                } }
                assertTrue("Unreadable/corrupt state is not absent", read.isFailure)
                assertTrue("Existing state must not be replaced", write.isFailure)
                assertTrue(answer.isFailure)
                assertEquals(0, posts.get())
            } finally { if (unreadable) assertTrue(sessions.setReadable(true, true)) }
            assertEquals(original, sessions.readText())
            if (unreadable) assertEquals("existing admission", Diary(sessions.parentFile!!).session("runs:existing.run"))
        }
    }

    @Test fun terminalFailuresAllowNewQuestionButNotRetry() = runBlocking {
        for (state in listOf("failed", "cancelled", "interrupted")) {
            val posts = java.util.concurrent.atomic.AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/runs") { e ->
                e.requestBody.readBytes()
                val text = if (e.requestMethod == "POST") {
                    posts.incrementAndGet(); """{"run_id":"r"}"""
                } else """{"status":"$state","session_id":"saved","error":"tool failed"}"""
                val bytes = text.toByteArray(); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
            }
            server.start()
            val dir = Files.createTempDirectory("terminal-run").toFile()
            try {
                val diary = Diary(dir)
                suspend fun ask(q: String, replay: Boolean = false) = runCatching { withContext(ConversationContext(diary, "c", replay = replay)) {
                    HermesRuns.answer("http://127.0.0.1:${server.address.port}", "key", JSONObject().put("input", q), coroutineContext[ConversationContext], "scope")
                } }
                assertTrue(ask("first").exceptionOrNull()!!.message!!.contains("tool failed"))
                assertNull(diary.session("scope.run"))
                assertEquals("saved", diary.session("scope"))
                assertTrue(ask("first", true).isFailure)
                assertEquals(1, posts.get())
                assertTrue(ask("next").exceptionOrNull()!!.message!!.contains("tool failed"))
                assertEquals(2, posts.get())
            } finally { server.stop(0); dir.deleteRecursively() }
        }
    }

    @Test fun lostAdmissionReplaysExactPersistedPayloadOnce() = runBlocking {
        val requests = mutableListOf<String>()
        val keys = mutableListOf<String>()
        val executions = mutableSetOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/runs") { e ->
            val text = if (e.requestMethod == "POST") {
                requests += e.requestBody.bufferedReader().readText()
                val key = e.requestHeaders.getFirst("Idempotency-Key"); keys += key
                executions += key
                // Admission committed, response lost. Malformed response avoids HTTP's internal reconnect.
                if (requests.size == 1) "{}" else """{"run_id":"same_run"}"""
            } else """{"status":"completed","output":"recovered"}"""
            val bytes = text.toByteArray(); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        val dir = Files.createTempDirectory("lost-admission").toFile()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            var diary = Diary(dir)
            suspend fun ask(model: String) = withContext(ConversationContext(diary, "c", replay = true)) {
                HermesRuns.answer(base, "key", JSONObject().put("input", "q").put("model", model).put("instructions", model), coroutineContext[ConversationContext], "scope")
            }
            withContext(ConversationContext(diary, "c")) {
                assertTrue(runCatching { HermesRuns.answer(base, "key", JSONObject().put("input", "q").put("model", "original").put("instructions", "original"), coroutineContext[ConversationContext], "scope") }.isFailure)
            }
            diary = Diary(dir)
            assertEquals("recovered", ask("changed"))
            assertEquals(2, requests.size); assertEquals(requests[0], requests[1])
            assertEquals(keys[0], keys[1]); assertEquals(1, executions.size)
            assertNull(diary.session("scope.run"))
        } finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test fun expiredAndPhotoAdmissionsNeverReplay() = runBlocking {
        scenario { base, diary, posts ->
            for (photo in listOf(false, true)) {
                val body = JSONObject().put("input", if (photo) "data:image/jpeg;base64,private" else "question")
                suspend fun ask() = runCatching { withContext(ConversationContext(diary, "c")) {
                    HermesRuns.answer(base, "key", body, coroutineContext[ConversationContext], "scope")
                } }
                assertTrue(ask().isFailure)
                val record = JSONObject(diary.session("scope.run")!!)
                if (photo) assertFalse(record.has("body"))
                else { record.put("created", System.currentTimeMillis() - 3_600_001); diary.keepSession("scope.run", record.toString()) }
                assertTrue(ask().exceptionOrNull()!!.message!!.contains("new conversation"))
                diary.keepSession("scope.run", "")
            }
            assertEquals(2, posts.get())
        }
    }

    @Test fun definitiveRejectionAllowsNewQuestion() = runBlocking {
        val posts = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/runs") { e ->
            posts.incrementAndGet(); e.requestBody.readBytes(); e.sendResponseHeaders(400, -1); e.close()
        }
        server.start()
        val dir = Files.createTempDirectory("rejected-run").toFile()
        try {
            val diary = Diary(dir)
            for (q in listOf("first", "next")) {
                assertTrue(runCatching { withContext(ConversationContext(diary, "c")) {
                    HermesRuns.answer("http://127.0.0.1:${server.address.port}", "key", JSONObject().put("input", q), coroutineContext[ConversationContext], "scope")
                } }.isFailure)
                assertNull(diary.session("scope.run"))
            }
            assertEquals(2, posts.get())
        } finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test fun sessionCacheCannotEvictUnresolvedRuns() {
        val dir = Files.createTempDirectory("run-retention").toFile()
        try {
            val diary = Diary(dir)
            repeat(140) { diary.keepSession("runs:$it.run", "uncertain") }
            val restored = Diary(dir)
            repeat(140) { assertEquals("uncertain", restored.session("runs:$it.run")) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun httpErrorRecoveryIsBounded() = runBlocking {
        val count = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/runs") { e ->
            count.incrementAndGet(); e.requestBody.readBytes()
            e.sendResponseHeaders(503, -1); e.close()
        }
        server.start()
        val dir = Files.createTempDirectory("run-http-error").toFile()
        try {
            val diary = Diary(dir)
            repeat(2) {
                val failed = runCatching { withContext(ConversationContext(diary, "c")) {
                    HermesRuns.answer("http://127.0.0.1:${server.address.port}", "key", JSONObject().put("input", "q"), coroutineContext[ConversationContext], "runs:scope")
                } }
                assertTrue(failed.isFailure)
            }
            assertEquals(2, count.get())
        } finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test fun ambiguousAdmissionStopsAfterOneRecovery() = runBlocking {
        scenario { base, diary, posts ->
            repeat(3) {
                val outcome = runCatching { withContext(ConversationContext(diary, "c")) {
                    HermesRuns.answer(base, "key", JSONObject().put("input", "question"), coroutineContext[ConversationContext], "scope")
                } }
                assertTrue(outcome.isFailure)
            }
            assertEquals(2, posts.get())
        }
    }

    private suspend fun scenario(block: suspend (String, Diary, java.util.concurrent.atomic.AtomicInteger) -> Unit) {
        val posts = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/runs") { e ->
            if (e.requestMethod == "POST") posts.incrementAndGet()
            e.requestBody.readBytes()
            val data = "{}".toByteArray() // Lost/malformed admission, no run ID.
            e.sendResponseHeaders(200, data.size.toLong()); e.responseBody.use { it.write(data) }
        }
        server.start()
        val dir = Files.createTempDirectory("ambiguous-run").toFile()
        try { block("http://127.0.0.1:${server.address.port}", Diary(dir), posts) }
        finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test fun knownImageRunResumesWithoutFreshPhoto() = runBlocking {
        val posts = java.util.concurrent.atomic.AtomicInteger()
        val polls = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            e.requestBody.readBytes()
            val text = when {
                e.requestURI.path == "/capabilities" -> """{"features":{"run_status":true}}"""
                e.requestMethod == "POST" -> { posts.incrementAndGet(); """{"run_id":"image_run"}""" }
                polls.incrementAndGet() == 1 -> """{"status":"waiting_for_approval"}"""
                else -> """{"status":"completed","output":"recovered"}"""
            }
            val bytes = text.toByteArray(); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        val dir = Files.createTempDirectory("image-resume").toFile()
        try {
            val diary = Diary(dir)
            val c = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, apiKey = "key", baseUrl = "http://127.0.0.1:${server.address.port}")
            withContext(ConversationContext(diary, "c")) {
                assertTrue(runCatching { HermesRuns.answer(c.api, c.apiKey, JSONObject().put("input", "data:image/jpeg;base64,AQ=="), coroutineContext[ConversationContext], "runs:" + java.security.MessageDigest.getInstance("SHA-256").digest("${c.api.trimEnd('/')}\u0000${c.apiKey}\u0000c".toByteArray()).joinToString("") { "%02x".format(it) }, inputIdentity = "question") }.exceptionOrNull()!!.message!!.contains("awaits approval"))
            }
            withContext(ConversationContext(diary, "c", replay = true)) {
                assertEquals("recovered", Brain({ c }).answer("question", Locale.ENGLISH, capturePhoto = { error("must not capture") }).text)
            }
            assertEquals(1, posts.get())
        } finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test fun pendingRunSurvivesCapabilityOutage() = pendingRunScenario(false)
    @Test fun modelChangeCannotAbandonPendingRun() = pendingRunScenario(true)

    private fun pendingRunScenario(changeModel: Boolean) = runBlocking {
        val posts = java.util.concurrent.atomic.AtomicInteger()
        val capabilities = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            val data = when (e.requestURI.path) {
                "/capabilities" -> if (capabilities.incrementAndGet() == 1) """{"features":{"run_status":true}}""" else "{}"
                "/runs" -> { posts.incrementAndGet(); """{"run_id":"run_pending"}""" }
                "/runs/run_pending" -> """{"run_id":"run_pending","status":"waiting_for_approval","session_id":"s"}"""
                else -> { posts.incrementAndGet(); """{"choices":[{"message":{"content":"unsafe fallback"}}]}""" }
            }.toByteArray()
            e.sendResponseHeaders(200, data.size.toLong()); e.responseBody.use { it.write(data) }
        }
        server.start()
        val dir = Files.createTempDirectory("run-capability").toFile()
        try {
            val diary = Diary(dir)
            val c = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, apiKey = "key", baseUrl = "http://127.0.0.1:${server.address.port}")
            repeat(2) {
                val result = runCatching { withContext(ConversationContext(diary, "c")) { Brain({ if (changeModel && it == 1) c.copy(model = "changed") else c }).answer("question", Locale.ENGLISH) } }
                assertTrue(result.isFailure)
            }
            assertEquals(1, posts.get())
        } finally { server.stop(0); dir.deleteRecursively() }
    }
    @Test fun cancelledObserverResumesKnownRunWithoutPostOrStop() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val polls = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/runs") { e ->
            paths += "${e.requestMethod} ${e.requestURI.path}"
            e.requestBody.readBytes()
            val text = if (e.requestMethod == "POST") """{"run_id":"run_resume"}""" else {
                if (polls.incrementAndGet() == 1) { entered.countDown(); release.await() }
                """{"run_id":"run_resume","session_id":"s","status":"completed","output":"recovered"}"""
            }
            try {
                val bytes = text.toByteArray(); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
            } catch (_: Exception) { }
        }
        server.start()
        val dir = Files.createTempDirectory("cancel-run").toFile()
        try {
            var diary = Diary(dir)
            val base = "http://127.0.0.1:${server.address.port}"
            suspend fun answer() = withContext(ConversationContext(diary, "c")) {
                HermesRuns.answer(base, "key", JSONObject().put("input", "q"), coroutineContext[ConversationContext], "scope")
            }
            val pending = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { answer() }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            pending.cancel(); pending.join(); release.countDown()
            diary = Diary(dir) // Process recreation reads durable run identity.
            assertEquals("recovered", answer())
            assertEquals(1, paths.count { it.startsWith("POST") })
            assertFalse(paths.any { it.endsWith("/stop") })
        } finally { release.countDown(); server.stop(0); dir.deleteRecursively() }
    }

    @Test fun imageLeaseRevokedBeforeWriteSendsNoBytes() = runBlocking {
        val host = java.util.UUID.randomUUID().toString()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val bytes = java.io.ByteArrayOutputStream()
        var allowed = true
        TestHttpConnections.handlers[host] = { url -> object : java.net.HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream(): java.io.OutputStream {
                entered.countDown(); release.await(); return bytes
            }
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        } }
        try {
            val pending = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
                withContext(LiveImageLease { check(allowed) }) {
                    HermesRuns.answer("visor-test://$host", "key", JSONObject().put("input", "image"), null, "scope")
                }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            allowed = false; release.countDown()
            assertTrue(runCatching { pending.await() }.isFailure)
            assertEquals(0, bytes.size())
        } finally { release.countDown(); TestHttpConnections.handlers.remove(host) }
    }

    @Test fun failedAndPartialRunsNeverReturnAnswer() = runBlocking {
        for (state in listOf("failed", "cancelled", "interrupted", "unknown", "completed")) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/runs") { e ->
                e.requestBody.readBytes()
                val bytes = (if (e.requestMethod == "POST") """{"run_id":"run_error"}"""
                    else """{"run_id":"run_error","status":"$state","output":"unsafe","partial":true,"error":"failure"}""").toByteArray()
                e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                assertTrue(runCatching { HermesRuns.answer("http://127.0.0.1:${server.address.port}", "key", JSONObject().put("input", "q"), null, "scope") }.isFailure)
            } finally { server.stop(0) }
        }
    }

    @Test fun createsRunPollsOutputAndContinuesSession() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val keys = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            val response = when {
                e.requestURI.path == "/capabilities" -> """{"features":{"run_status":true,"session_continuity_header":"X-Hermes-Session-Id"}}"""
                e.requestURI.path == "/runs" -> {
                    requests += JSONObject(e.requestBody.bufferedReader().readText())
                    keys += e.requestHeaders.getFirst("Idempotency-Key").orEmpty()
                    """{"run_id":"run_test","status":"started"}"""
                }
                e.requestURI.path == "/runs/run_test" -> """{"run_id":"run_test","session_id":"session_test","status":"completed","completed":true,"output":"answer"}"""
                e.requestURI.path.startsWith("/api/sessions/") -> "{}"
                else -> """{"choices":[{"message":{"content":"wrong endpoint"}}]}"""
            }.toByteArray()
            e.sendResponseHeaders(200, response.size.toLong()); e.responseBody.use { it.write(response) }
        }
        server.start()
        val dir = Files.createTempDirectory("brain-runs").toFile()
        try {
            val diary = Diary(dir)
            val c = AssistantConfig(provider = Provider.OPENAI_COMPATIBLE, apiKey = "key", baseUrl = "http://127.0.0.1:${server.address.port}")
            suspend fun ask(q: String) = withContext(ConversationContext(diary, "conversation")) { Brain({ c }).answer(q, Locale.ENGLISH) }
            assertEquals("answer", ask("first").text)
            assertEquals("answer", ask("next").text)
            assertEquals(2, requests.size)
            assertFalse(requests[0].getString("instructions").contains("remember tool"))
            assertFalse(requests[0].getString("instructions").contains("end_conversation"))
            assertTrue(requests[0].getString("instructions").contains("Hermes"))
            assertEquals("first", requests[0].getString("input"))
            assertEquals("session_test", requests[1].getString("session_id"))
            assertFalse(requests[1].has("conversation_history"))
            assertTrue(keys.all { it.isNotBlank() }); assertNotEquals(keys[0], keys[1])
        } finally { server.stop(0); dir.deleteRecursively() }
    }
}

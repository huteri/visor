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
    @Test fun lostFirstAdmissionAllowsDistinctQuestionWithOriginalContextAfterRestart() = runBlocking {
        val posts = mutableListOf<JSONObject>()
        val keys = mutableListOf<String>()
        val paths = mutableListOf<String>()
        var originalSession = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            paths += "${e.requestMethod} ${e.requestURI.path}"
            val text = when {
                e.requestURI.path == "/capabilities" -> """{"features":{"run_status":true}}"""
                e.requestURI.path == "/runs" -> {
                    posts += JSONObject(e.requestBody.bufferedReader().readText())
                    keys += e.requestHeaders.getFirst("Idempotency-Key")
                    if (posts.size == 1) {
                        // The server accepted and executed the first turn, but its reply was lost.
                        originalSession = posts[0].optString("session_id", "server_generated")
                        "{}"
                    } else if (posts.size == 2) """{"run_id":"second"}"""
                    else """{"run_id":"original"}"""
                }
                e.requestURI.path == "/runs/original" -> """{"status":"completed","session_id":"$originalSession","output":"original result"}"""
                e.requestURI.path == "/runs/second" -> """{"status":"completed","session_id":"$originalSession","output":"context retained"}"""
                e.requestURI.path == "/api/sessions/$originalSession" -> "{}"
                else -> error("Unexpected request ${e.requestURI}")
            }
            val bytes = text.toByteArray(); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        val dir = Files.createTempDirectory("lost-first-distinct").toFile()
        try {
            var diary = Diary(dir)
            val c = AssistantConfig(provider = Provider.HERMES, apiKey = "key", baseUrl = "http://127.0.0.1:${server.address.port}")
            suspend fun ask(q: String) = withContext(ConversationContext(diary, "c")) {
                Brain({ c }).answer(q, Locale.ENGLISH, listOf(Exchange("prior question", "prior answer"))).text
            }
            assertTrue(runCatching { ask("first action") }.isFailure)
            val admissionBefore = java.io.File(dir, "sessions.json").readText()
            diary = Diary(dir)
            assertEquals("context retained", ask("distinct question"))
            assertEquals(2, posts.size)
            assertTrue(posts[0].getString("session_id").isNotBlank())
            assertEquals(originalSession, posts[1].getString("session_id"))
            assertEquals("prior question", posts[0].getJSONArray("conversation_history").getJSONObject(0).getString("content"))
            assertFalse(posts[1].has("conversation_history"))
            assertNotEquals(keys[0], keys[1])
            assertEquals(listOf("first action", "distinct question"), posts.map { it.getString("input") })
            val before = JSONObject(admissionBefore)
            val oldSlot = before.keys().asSequence().single { it.endsWith(".run") }
            assertEquals(before.getString(oldSlot), JSONObject(java.io.File(dir, "sessions.json").readText()).getString(oldSlot))
            diary = Diary(dir)
            assertEquals("original result", ask("first action"))
            assertEquals(3, posts.size)
            assertEquals(posts[0].toString(), posts[2].toString())
            assertEquals(keys[0], keys[2])
            assertEquals(2, keys.toSet().size) // Only the two distinct actions can execute.
            assertNull(diary.session(oldSlot))
            assertFalse(paths.any { it.endsWith("/approval") || it.endsWith("/stop") })
        } finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test fun missingTranscriptOrLegacyUnknownIdentityNeverStartsContextlessDistinctRun() = runBlocking {
        for (legacy in listOf(false, true)) {
            val posts = java.util.concurrent.atomic.AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { e ->
                val text = when (e.requestURI.path) {
                    "/capabilities" -> """{"features":{"run_status":true}}"""
                    "/runs" -> { posts.incrementAndGet(); e.requestBody.readBytes(); "{}" }
                    else -> "{}"
                }
                val bytes = text.toByteArray()
                e.sendResponseHeaders(if (e.requestURI.path.startsWith("/api/sessions/")) 404 else 200, bytes.size.toLong())
                e.responseBody.use { it.write(bytes) }
            }
            server.start()
            val dir = Files.createTempDirectory("missing-run-context").toFile()
            try {
                var diary = Diary(dir)
                val c = AssistantConfig(provider = Provider.HERMES, apiKey = "key", baseUrl = "http://127.0.0.1:${server.address.port}")
                suspend fun ask(q: String) = withContext(ConversationContext(diary, "c")) { Brain({ c }).answer(q, Locale.ENGLISH) }
                assertTrue(runCatching { ask("first") }.isFailure)
                val saved = JSONObject(java.io.File(dir, "sessions.json").readText())
                val slot = saved.keys().asSequence().single { it.endsWith(".run") }
                if (legacy) {
                    val record = JSONObject(saved.getString(slot))
                    record.remove("session_id")
                    record.put("body", JSONObject(record.getString("body")).apply { remove("session_id") }.toString())
                    diary.keepSession(slot, record.toString())
                }
                val original = diary.session(slot)
                diary = Diary(dir)
                val failure = runCatching { ask("distinct") }.exceptionOrNull()
                assertTrue(failure!!.message!!, failure.message!!.contains(if (legacy) "identity unknown" else "conversation unavailable"))
                assertEquals(1, posts.get())
                assertEquals(original, diary.session(slot))
            } finally { server.stop(0); dir.deleteRecursively() }
        }
    }

    @Test fun cancelledBeforeSessionObservationRecoversSessionForDistinctQuestion() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val posts = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val polls = java.util.concurrent.atomic.AtomicInteger()
        var originalSession = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            paths += "${e.requestMethod} ${e.requestURI.path}"
            val text = when {
                e.requestURI.path == "/capabilities" -> """{"features":{"run_status":true}}"""
                e.requestURI.path == "/runs" -> {
                    posts += JSONObject(e.requestBody.bufferedReader().readText())
                    if (posts.size == 1) originalSession = posts[0].getString("session_id")
                    """{"run_id":"run_${posts.size}"}"""
                }
                e.requestURI.path == "/runs/run_1" -> {
                    if (polls.incrementAndGet() == 1) { entered.countDown(); release.await() }
                    // The caller-selected identity is available before any status response.
                    """{"status":"running","session_id":"$originalSession"}"""
                }
                e.requestURI.path.startsWith("/api/sessions/") -> "{}"
                else -> """{"status":"completed","session_id":"$originalSession","output":"answer"}"""
            }
            val bytes = text.toByteArray()
            runCatching { e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) } }
            e.close()
        }
        server.start()
        val dir = Files.createTempDirectory("cancel-distinct-runs").toFile()
        try {
            var diary = Diary(dir)
            val c = AssistantConfig(provider = Provider.HERMES, apiKey = "key", baseUrl = "http://127.0.0.1:${server.address.port}")
            suspend fun ask(q: String) = withContext(ConversationContext(diary, "c")) { Brain({ c }).answer(q, Locale.ENGLISH).text }
            val first = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { ask("first") }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            first.cancel(); first.join(); release.countDown()
            diary = Diary(dir)
            assertEquals("answer", ask("next"))
            assertEquals(2, posts.size)
            assertEquals(originalSession, posts[1].optString("session_id"))
            assertFalse(posts[1].has("conversation_history"))
            assertEquals(1, polls.get()) // No status lookup or waiting on the unrelated older run.
            assertFalse(paths.any { it.endsWith("/approval") || it.endsWith("/stop") })
        } finally { release.countDown(); server.stop(0); dir.deleteRecursively() }
    }

    @Test fun distinctQuestionContinuesSessionAndRetainsOlderRunAfterRestart() = runBlocking {
        val posts = mutableListOf<JSONObject>()
        val paths = mutableListOf<String>()
        var finish = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            paths += "${e.requestMethod} ${e.requestURI.path}"
            val text = when {
                e.requestURI.path == "/capabilities" -> """{"features":{"run_status":true}}"""
                e.requestURI.path == "/runs" -> {
                    posts += JSONObject(e.requestBody.bufferedReader().readText())
                    """{"run_id":"run_${posts.size}"}"""
                }
                e.requestURI.path == "/runs/run_1" && !finish -> """{"status":"waiting_for_approval","session_id":"same_session"}"""
                e.requestURI.path.startsWith("/api/sessions/") -> "{}"
                else -> """{"status":"completed","session_id":"same_session","output":"answer"}"""
            }
            val bytes = text.toByteArray(); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        val dir = Files.createTempDirectory("distinct-runs").toFile()
        try {
            var diary = Diary(dir)
            val c = AssistantConfig(provider = Provider.HERMES, apiKey = "key", baseUrl = "http://127.0.0.1:${server.address.port}")
            suspend fun ask(q: String, replay: Boolean = false) = withContext(ConversationContext(diary, "c", replay)) {
                Brain({ c }).answer(q, Locale.ENGLISH).text
            }
            assertTrue(runCatching { ask("first") }.exceptionOrNull()!!.message!!.contains("awaits approval"))
            diary = Diary(dir)
            assertEquals("answer", ask("next"))
            assertEquals("same_session", posts[1].getString("session_id"))
            assertFalse(posts[1].has("conversation_history"))
            diary = Diary(dir); finish = true
            assertEquals("answer", ask("first", replay = true))
            assertEquals(2, posts.size)
            assertFalse(paths.any { it.endsWith("/stop") })
        } finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test fun serverConflictSurfacesReasonAndPreservesOlderAdmission() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val posts = java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/runs") { e ->
            posts.incrementAndGet(); e.requestBody.readBytes()
            val bytes = """{"error":{"code":"session_busy","message":"Session already running"}}""".toByteArray()
            e.sendResponseHeaders(409, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        val dir = Files.createTempDirectory("busy-run").toFile()
        try {
            val diary = Diary(dir)
            val legacy = JSONObject().put("input", "old").put("run_id", "older").toString()
            diary.keepSession("scope.run", legacy)
            val failure = runCatching { withContext(ConversationContext(diary, "c")) {
                HermesRuns.answer("http://127.0.0.1:${server.address.port}", "key", JSONObject().put("input", "next").put("session_id", "same"), coroutineContext[ConversationContext], "scope")
            } }.exceptionOrNull()
            assertTrue(failure!!.message!!, failure.message!!.contains("409") && failure.message!!.contains("session_busy"))
            assertEquals(1, posts.get())
            assertEquals(legacy, Diary(dir).session("scope.run"))
            assertNull(Diary(dir).session(HermesRuns.slot("scope", "next")))
        } finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test fun failedDirectorySyncSendsNoPostAndRetainsAdmissions() = runBlocking {
        scenario { base, diary, posts ->
            val dir = diary.photos.parentFile!!
            diary.keepSession("runs:existing.run", "existing admission")
            var syncs = 0
            val failing = Diary(dir, syncSessionDirectory = { parent ->
                assertEquals(dir, parent)
                // Rename already landed; do not roll back or erase uncertain admission.
                assertTrue(java.io.File(parent, "sessions.json").readText().contains(HermesRuns.slot("runs:new", "q")))
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
            assertNotNull(restored.session(HermesRuns.slot("runs:new", "q")))
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
                assertNull(diary.session(HermesRuns.slot("scope", "first")))
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
            assertNull(diary.session(HermesRuns.slot("scope", "q")))
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
                val slot = HermesRuns.slot("scope", body.get("input").toString())
                val record = JSONObject(diary.session(slot)!!)
                if (photo) assertFalse(record.has("body"))
                else { record.put("created", System.currentTimeMillis() - 3_600_001); diary.keepSession(slot, record.toString()) }
                assertTrue(ask().exceptionOrNull()!!.message!!.contains("new conversation"))
                diary.keepSession(slot, "")
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
                assertNull(diary.session(HermesRuns.slot("scope", q)))
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
            val c = AssistantConfig(provider = Provider.HERMES, apiKey = "key", baseUrl = "http://127.0.0.1:${server.address.port}")
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
            val c = AssistantConfig(provider = Provider.HERMES, apiKey = "key", baseUrl = "http://127.0.0.1:${server.address.port}")
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
            val c = AssistantConfig(provider = Provider.HERMES, apiKey = "key", baseUrl = "http://127.0.0.1:${server.address.port}")
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

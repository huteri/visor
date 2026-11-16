package com.macedonga.visor.core.assistant

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HermesRunsApprovalTest {
    @Test fun approvalRequiresExplicitConsentAndRetainsRun() = fixture { f ->
        f.states += waiting("one")
        f.allowApprovals = false
        assertTrue(runCatching { f.ask() }.exceptionOrNull()!!.message!!.contains("approval"))
        assertEquals(0, f.approvals.size)
        assertNotNull(f.diary.session("scope.run"))
        f.allowApprovals = true
        f.states += completed
        assertEquals("answer", f.ask(replay = true))
        assertEquals(1, f.admissions)
        assertEquals(1, f.approvals.size)
    }

    @Test fun approvesEachExactRequestOnceAndKeepsPolling() = fixture { f ->
        f.states += listOf(waiting("one"), waiting("one"), waiting("two"), completed)
        assertEquals("answer", f.ask())
        assertEquals(listOf("one", "two"), f.approvals.map { it.getString("request_id") })
        f.approvals.forEach { assertEquals(setOf("choice", "request_id"), it.keys().asSequence().toSet()); assertEquals("once", it.getString("choice")) }
        assertEquals(1, f.admissions)
        assertNull(f.diary.session("scope.run"))
    }

    @Test fun progressingApprovalsResetStalePollBudgetForEachRequest() = fixture { f ->
        val ids = (1..21).map { "request-$it" }
        ids.forEach { id -> repeat(2) { f.states += waiting(id) } }
        f.states += completed
        assertEquals("answer", f.ask())
        assertEquals(ids, f.approvals.map { it.getString("request_id") })
        assertEquals(1, f.admissions)
        assertNull(f.diary.session("scope.run"))
    }

    @Test fun resumedExecutionResetsStalePollBudgetWithoutRepeatingApproval() = fixture { f ->
        repeat(11) { f.states += waiting("one") }
        f.states += """{"run_id":"owned","status":"running"}"""
        repeat(11) { f.states += waiting("one") }
        f.states += completed
        assertEquals("answer", f.ask())
        assertEquals(listOf("one"), f.approvals.map { it.getString("request_id") })
        assertEquals(1, f.admissions)
        assertNull(f.diary.session("scope.run"))
    }

    @Test fun distinctQuestionRetiresOnlyAuthoritativelyTerminalPriorRun() = fixture { f ->
        f.states += listOf(waiting("one"), completed, completed)
        f.approvalCode = 503
        assertTrue(runCatching { f.ask() }.isFailure)
        f.approvalCode = 200
        assertEquals("answer", f.ask("next"))
        assertEquals(2, f.admissions)
        assertEquals(1, f.approvals.size)
    }

    @Test fun approvalErrorResumesStoredRunWithoutRepeatingApproval() = fixture { f ->
        f.states += listOf(waiting("one"), waiting("one"), completed)
        f.approvalCode = 503
        assertTrue(runCatching { f.ask() }.isFailure)
        f.diary = Diary(f.dir)
        assertEquals("answer", f.ask(replay = true))
        assertEquals(1, f.admissions)
        assertEquals(1, f.approvals.size)
    }

    @Test fun exactConflictRaceContinuesPolling() = fixture { f ->
        f.states += listOf(waiting("one"), completed)
        f.approvalCode = 409
        assertEquals("answer", f.ask())
        assertEquals(1, f.approvals.size)
    }

    @Test fun foreignAndMalformedApprovalFailClosed() = fixture { f ->
        for (state in listOf(waiting("one").replace("\"run_id\":\"owned\",\"event\"", "\"run_id\":\"foreign\",\"event\""), waiting(""), waiting("one").replace("\"once\",", ""))) {
            f.states.clear(); f.states += state
            assertTrue(runCatching { f.ask() }.isFailure)
        }
        assertEquals(0, f.approvals.size)
        assertEquals(1, f.admissions)
    }

    @Test fun distinctQuestionCannotApproveOrAbandonActiveRun() = fixture { f ->
        f.states += waiting("one")
        f.approvalCode = 503
        assertTrue(runCatching { f.ask() }.isFailure)
        assertTrue(runCatching { f.ask("next") }.isFailure)
        assertEquals(1, f.admissions)
        assertEquals(1, f.approvals.size)
        assertNotNull(f.diary.session("scope.run"))
    }

    @Test fun cancellationLeavesRunAndApprovalResumeSafe() = fixture { f ->
        f.states += waiting("one")
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async { f.ask() }
        while (f.approvals.isEmpty()) kotlinx.coroutines.delay(10)
        job.cancel(); job.join()
        f.states.clear(); f.states += completed
        f.diary = Diary(f.dir)
        assertEquals("answer", f.ask())
        assertEquals(1, f.admissions)
        assertEquals(1, f.approvals.size)
    }

    @Test fun existingMatchingPendingRunApprovesWithoutAdmission() = fixture { f ->
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest("question".toByteArray()).joinToString("") { "%02x".format(it) }
        f.diary.keepSession("scope.run", JSONObject().put("input", fingerprint).put("run_id", "owned").toString())
        f.states += listOf(waiting("one"), completed)
        assertEquals("answer", f.ask(replay = true))
        assertEquals(0, f.admissions)
        assertEquals(1, f.approvals.size)
    }

    @Test fun unchangedApprovalStopsPollingWithoutRepost() = fixture { f ->
        f.states += waiting("one")
        assertTrue(runCatching { f.ask() }.exceptionOrNull()!!.message!!.contains("still pending"))
        assertEquals(1, f.approvals.size)
        assertNotNull(f.diary.session("scope.run"))
    }

    private val completed = """{"run_id":"owned","status":"completed","output":"answer"}"""
    private fun waiting(id: String) = """{"run_id":"owned","status":"waiting_for_approval","approval":{"run_id":"owned","event":"approval.request","request_id":"$id","choices":["once","session","always","deny"],"command":"private"}}"""

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val f = Fixture()
        try { block(f) } finally { f.server.stop(0); f.dir.deleteRecursively() }
    }

    private class Fixture {
        val states = java.util.concurrent.CopyOnWriteArrayList<String>()
        val approvals = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        var admissions = 0
        var approvalCode = 200
        var allowApprovals = true
        val dir = Files.createTempDirectory("runs-approval").toFile()
        var diary = Diary(dir)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/runs") { e ->
                val body = e.requestBody.bufferedReader().readText()
                val response = when {
                    e.requestMethod == "POST" && e.requestURI.path == "/runs" -> { admissions++; """{"run_id":"owned"}""" }
                    e.requestMethod == "POST" && e.requestURI.path == "/runs/owned/approval" -> {
                        approvals += JSONObject(body)
                        """{"object":"hermes.run.approval_response","run_id":"owned","choice":"once","request_id":"${approvals.last().getString("request_id")}","resolved":1}"""
                    }
                    e.requestMethod == "GET" && e.requestURI.path == "/runs/owned" -> if (states.size > 1) states.removeAt(0) else states.single()
                    else -> error("Unexpected HTTP path")
                }
                val bytes = (if (e.requestURI.path.endsWith("/approval") && approvalCode == 409) """{"error":{"code":"approval_not_pending"}}""" else response).toByteArray()
                e.sendResponseHeaders(if (e.requestURI.path.endsWith("/approval")) approvalCode else 200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
            }
            start()
        }
        suspend fun ask(q: String = "question", replay: Boolean = false) = withContext(ConversationContext(diary, "c", replay = replay)) {
            HermesRuns.answer("http://127.0.0.1:${server.address.port}", "secret", JSONObject().put("input", q), coroutineContext[ConversationContext], "scope", allowApprovals = allowApprovals)
        }
    }
}

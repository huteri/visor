package com.macedonga.visor.core.assistant

import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

/** Polling observers never own the server task. No cancellation path sends /stop. */
internal object HermesRuns {
    private val log = java.util.logging.Logger.getLogger("HermesRuns")
    suspend fun answer(base: String, key: String, body: JSONObject, conversation: ConversationContext?, scope: String, inputIdentity: String = body.get("input").toString()): String {
        val diary = conversation?.diary
        conversation?.validate()
        val fingerprint = fingerprint(inputIdentity)
        // Read old single-slot admissions in place; new questions own independent durable slots.
        val legacy = diary?.session("$scope.run")?.let(::JSONObject)
        val slot = if (legacy?.getString("input") == fingerprint) "$scope.run" else slot(scope, inputIdentity)
        val previous = diary?.session(slot)?.let(::JSONObject)
        val recovery = "Tools may already have run. In Live AI say 'new conversation', or tap 'New conversation' in the app; request not replayed."
        // Runs supports a caller-selected body.session_id, including the first turn with imported
        // history. Reserve it in the admission BEFORE POST, rather than depending on a reply to
        // learn identity. Never assign a fresh session to an older uncertain admission.
        if (previous == null && conversation != null && !body.has("session_id")) {
            body.put("session_id", "visor_${UUID.randomUUID()}")
        }
        val record = previous ?: JSONObject().put("key", UUID.randomUUID().toString()).put("input", fingerprint)
            .put("created", System.currentTimeMillis()).put("attempts", 0).apply {
                body.optString("session_id").takeIf { it.isNotBlank() }?.let { put("session_id", it) }
                // Never persist photos, including photos in imported history. Those admissions cannot be replayed.
                if (!body.toString().contains("data:image", ignoreCase = true)) put("body", body.toString())
            }
        fun save() {
            if (diary != null) synchronized(diary) {
                conversation.validate()
                diary.keepSession(slot, record.toString())
            }
        }
        if (!record.has("run_id")) {
            if (previous == null) check(conversation?.replay != true) { "Hermes Retry/Again cannot replay a completed request." }
            else {
                // Server retains keys for 24h. One replay within 1h leaves a large transport/clock margin.
                val age = System.currentTimeMillis() - record.optLong("created", 0)
                check(record.has("body") && age in 0..3_600_000 && record.optInt("attempts", 2) < 2) {
                    "Hermes admission cannot be recovered safely. $recovery"
                }
            }
            record.put("attempts", record.optInt("attempts") + 1)
            log.info("Hermes Runs admission attempt")
            save() // Persist key and exact text-only payload BEFORE any bytes.
            val accepted = try {
                request("${base.trimEnd('/')}/runs", key, if (previous == null) body.toString() else record.getString("body"), record.getString("key"))
            } catch (e: AdmissionRejected) {
                // On initial admission, 4xx (except timeout) proves rejection. A replay rejection
                // cannot prove the first admission did not execute, so keep its tombstone.
                if (previous == null && diary != null) synchronized(diary) {
                    conversation.validate(); diary.keepSession(slot, "")
                }
                if (previous != null) error("Hermes recovery rejected. $recovery")
                throw e
            }
            val id = accepted.getString("run_id")
            check(id.matches(Regex("[A-Za-z0-9_-]{1,512}"))) { "Invalid Hermes run ID" }
            record.put("run_id", id).remove("body")
            save()
        }
        var approvalPolls = 0
        var pendingApproval: String? = null
        while (true) {
            coroutineContext.ensureActive(); conversation?.validate()
            val status = request("${base.trimEnd('/')}/runs/${record.getString("run_id")}", key)
            status.optString("session_id").takeIf { it.isNotBlank() }?.let { id ->
                if (diary != null) synchronized(diary) {
                    conversation.validate(); diary.keepSession(scope, id)
                }
            }
            val state = status.getString("status")
            log.info("Hermes Runs state " + state.takeIf { it in setOf("completed", "failed", "cancelled", "interrupted", "waiting_for_approval", "queued", "running", "stopping") }.orEmpty())
            when (state) {
                "completed" -> {
                    check(!status.optBoolean("partial") && !status.optBoolean("interrupted") && status.optBoolean("completed", true)) { "Hermes returned incomplete output" }
                    val output = status.getString("output")
                    check(output.isNotBlank()) { "Hermes returned empty output" }
                    if (diary != null) synchronized(diary) {
                        conversation.validate(); diary.keepSession(slot, "")
                    }
                    log.info("Hermes Runs completion")
                    return output
                }
                "failed", "cancelled", "interrupted" -> {
                    if (diary != null) synchronized(diary) {
                        conversation.validate(); diary.keepSession(slot, "")
                    }
                    error("Hermes run $state: ${status.optString("error", "request not replayed")}")
                }
                "waiting_for_approval" -> {
                    val approval = status.optJSONObject("approval")
                    val requestId = approval?.opt("request_id") as? String
                    check(approval != null && approval.optString("run_id") == record.getString("run_id") &&
                        requestId != null && requestId.isNotBlank() && requestId == requestId.trim() && requestId.length <= 256 &&
                        approval.optJSONArray("choices")?.let { choices -> (0 until choices.length()).any { choices.optString(it) == "once" } } == true) {
                        "Hermes run awaits approval with unsupported request identity; resume original question."
                    }
                    if (pendingApproval != requestId) {
                        pendingApproval = requestId
                        approvalPolls = 0
                    }
                    val attempted = record.optJSONObject("approvals") ?: JSONObject().also { record.put("approvals", it) }
                    if (!attempted.has(requestId)) {
                        check(attempted.length() < 128) { "Hermes approval limit reached; request not replayed." }
                        attempted.put(requestId, true)
                        save() // Ambiguous responses must never re-submit this exact approval, even after restart.
                        log.info("Hermes Runs approval attempt")
                        try {
                            request("${base.trimEnd('/')}/runs/${record.getString("run_id")}/approval", key,
                                JSONObject().put("request_id", requestId).put("choice", "once").toString())
                        } catch (e: HttpFailure) {
                            if (e.code != 409 || e.reason !in setOf("approval_not_active", "approval_not_pending")) throw e
                        }
                    }
                    check(++approvalPolls <= 20) { "Hermes approval still pending; resume original question. Request not replayed." }
                    delay(500)
                }
                "queued", "running", "stopping" -> {
                    pendingApproval = null
                    approvalPolls = 0
                    delay(500)
                }
                else -> error("Unknown Hermes run status: $state")
            }
        }
    }

    /** Recover identity only; the server owns admission and run/conversation policy. */
    internal suspend fun recoverSession(base: String, key: String, conversation: ConversationContext, scope: String): String? {
        conversation.validate()
        val records = conversation.diary.pendingRuns(scope).map(::JSONObject)
        if (records.isEmpty()) return null
        val record = records.maxByOrNull { it.optLong("created", 0) }!!
        // A predeclared identity survives even a lost admission response. Brain still verifies
        // that its server transcript exists before submitting any distinct question.
        record.optString("session_id").takeIf { it.isNotBlank() }?.let { return it }
        check(record.has("run_id")) { "Hermes session identity unknown; resume original question. Request not replayed." }
        val id = record.getString("run_id")
        check(id.matches(Regex("[A-Za-z0-9_-]{1,512}"))) { "Invalid Hermes run ID" }
        val status = request("${base.trimEnd('/')}/runs/$id", key)
        val session = status.optString("session_id")
        check(session.isNotBlank()) { "Hermes session identity unavailable; resume original question. Request not replayed." }
        synchronized(conversation.diary) {
            conversation.validate(); conversation.diary.keepSession(scope, session)
        }
        return session
    }

    internal fun slot(scope: String, input: String) = "$scope.${fingerprint(input)}.run"

    internal fun pending(diary: Diary, scope: String, input: String): Boolean =
        diary.session(slot(scope, input)) != null || diary.session("$scope.run")?.let {
            JSONObject(it).getString("input") == fingerprint(input)
        } == true

    private fun fingerprint(input: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

    private class HttpFailure(val code: Int, val reason: String) : IllegalStateException("Hermes HTTP $code${reason.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}; request not replayed.")

    private class AdmissionRejected(code: Int, reason: String) : IllegalStateException("Hermes HTTP $code${reason.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}; admission rejected, request not replayed.")

    private suspend fun request(url: String, key: String, body: String? = null, idempotency: String? = null): JSONObject =
        suspendCancellableCoroutine { pending ->
            val http = URL(url).openConnection() as HttpURLConnection
            pending.invokeOnCancellation { Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { http.disconnect() }) }
            Dispatchers.IO.dispatch(pending.context, Runnable {
                try {
                    fun validate() {
                        pending.context.ensureActive()
                        pending.context[ConversationContext]?.validate()
                        pending.context[LiveImageLease]?.validate()
                    }
                    validate()
                    http.connectTimeout = 5_000; http.readTimeout = 10_000
                    http.setRequestProperty("Authorization", "Bearer $key")
                    if (body != null) {
                        http.requestMethod = "POST"; http.doOutput = true
                        http.setRequestProperty("Content-Type", "application/json")
                        if (idempotency != null) http.setRequestProperty("Idempotency-Key", idempotency)
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        http.outputStream.use { validate(); it.write(bytes) }
                    }
                    val code = http.responseCode
                    if (code !in 200..299) {
                        val reason = runCatching { http.errorStream?.bufferedReader()?.use { JSONObject(it.readText()).optJSONObject("error")?.optString("code") } }.getOrNull().orEmpty()
                        if (idempotency != null && code in 400..499 && code != 408) throw AdmissionRejected(code, reason)
                        throw HttpFailure(code, reason)
                    }
                    val answer = http.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                    validate(); pending.resume(answer)
                } catch (e: Exception) { pending.resumeWithException(e) }
                finally { http.disconnect() }
            })
        }
}

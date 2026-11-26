@file:OptIn(KitInternal::class)

package com.macedonga.visor.outside

import com.macedonga.visor.kit.protocol.JsonRpc
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.server.Caller
import com.macedonga.visor.kit.server.Dispatcher
import com.macedonga.visor.kit.server.JsonRpcSession
import com.macedonga.visor.log.EventLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Posts the events of each network client's webhook topics to its URL, while the network door is open. Each client
 * subscribes as itself, with its own scopes, through an ordinary JSON-RPC session. A body is
 * `{"client", "topic", "event", "time"}`, signed in `X-Visor-Signature: sha256=<hex>`: HMAC-SHA256 of the body, keyed
 * by the hex SHA-256 of the client's token, which the receiver can work out and Visor keeps.
 */
@Singleton
class KitWebhooks internal constructor(
    private val clients: NetworkClients,
    private val log: EventLog,
    private val scope: CoroutineScope,
    private val connect: (String) -> HttpURLConnection,
) {
    @Inject constructor(clients: NetworkClients, log: EventLog) : this(
        clients, log, CoroutineScope(SupervisorJob() + Dispatchers.IO),
        { URL(it).openConnection() as HttpURLConnection },
    )

    fun start(dispatcher: Dispatcher) {
        scope.launch {
            val hooks = mutableMapOf<String, Hook>()
            try {
                combine(clients.webhookEpoch, clients.all) { epoch, all -> epoch to all }.collect { (epoch, all) ->
                    for ((name, hook) in hooks.filterValues { !it.current() }) {
                        hooks.remove(name)
                        hook.end()
                    }
                    if (epoch != null) for (client in all) {
                        if (client.webhook != null && client.topics.isNotEmpty() && client.name !in hooks &&
                            clients.ownsWebhook(epoch, client)
                        ) hooks[client.name] = open(dispatcher, epoch, client)
                    }
                }
            } finally {
                hooks.values.forEach(Hook::end)
            }
        }
    }

    private class Hook(val current: () -> Boolean, val worker: Job, val queue: Channel<String>, val session: JsonRpcSession) {
        fun end() {
            worker.cancel()
            queue.cancel()
            session.end()
        }
    }

    private fun open(dispatcher: Dispatcher, epoch: Any, client: NetworkClient): Hook {
        val url = checkNotNull(client.webhook)
        val current = { clients.ownsWebhook(epoch, client) }
        // One at a time and in order, so a receiver sees events as they happened.
        val bodies = Channel<String>(Channel.UNLIMITED)
        val worker = scope.launch {
            val receiver = Receiver(client, url, epoch, current)
            try {
                for (body in bodies) receiver.post(body)
            } finally {
                bodies.cancel()
            }
        }
        val topics = client.topics.withIndex().associate { (i, topic) -> i + 1L to topic }
        val session = JsonRpcSession(
            dispatcher, Caller(NETWORK_UID, client.name, network = true),
            send = { message ->
                val m = JSONObject(message)
                val params = m.optJSONObject("params")
                when (m.optString("method")) {
                    JsonRpc.EVENT -> {
                        val topic = topics[params?.optLong("subscription")]
                        if (topic != null && current()) {
                            val body = JSONObject().put("client", client.name).put("topic", topic)
                                .put("event", params?.opt("event")).put("time", System.currentTimeMillis())
                            bodies.trySend(body.toString())
                        }
                    }
                    JsonRpc.ENDED -> log.add("Kit: ${client.name}'s webhook lost ${topics[params?.optLong("subscription")]}: ${params?.optJSONObject("failure")?.optString("message")}")
                }
                true
            },
            close = { worker.cancel(); bodies.cancel() },
        )
        for ((id, topic) in topics) session.receive(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", JsonRpc.SUBSCRIBE).put("params", JSONObject().put("topic", topic)).toString())
        log.add("Kit: ${client.name}'s webhook posts ${client.topics.joinToString()}")
        return Hook(current, worker, bodies, session)
    }

    private inner class Receiver(val client: NetworkClient, val url: String, val epoch: Any, val current: () -> Boolean) {
        private var down = false
        private var lastLog = 0L

        private suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            if (!current()) throw CancellationException("Webhook configuration retired")
        }

        // Retry transient failures. An already-down receiver gets one try per event until it recovers.
        suspend fun post(body: String) {
            val bytes = body.toByteArray()
            val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(client.tokenHash.toByteArray(), HMAC)) }
            val signature = mac.doFinal(bytes).joinToString("") { "%02x".format(it) }
            val tries = if (down) 1 else TRIES
            for (attempt in 1..tries) {
                val failure = send(bytes, signature)
                ensureCurrent()
                if (failure == null) {
                    down = false
                    return
                }
                if (!failure.passing || attempt == tries) {
                    if (failure.passing) down = true
                    quietly(failure.what)
                    return
                }
                delay(RETRY_MS * attempt)
            }
        }

        /** Posts [bytes] once. Null when the receiver took them. */
        private suspend fun send(bytes: ByteArray, signature: String): Failure? = try {
            ensureCurrent()
            val connection = connect(url)
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Visor-Signature", "sha256=$signature")
                ensureCurrent()
                connection.outputStream.use { stream ->
                    // Stream acquisition can block across revocation; admit only after it returns.
                    currentCoroutineContext().ensureActive()
                    val write = clients.admitWebhookWrite(epoch, client) { stream.write(bytes) }
                        ?: throw CancellationException("Webhook configuration retired")
                    write()
                }
                ensureCurrent()
                val code = connection.responseCode
                if (code in 200..299) null else Failure("answered $code", code == 429 || code >= 500)
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            Failure("failed: ${e.message}", passing = true)
        }

        private fun quietly(what: String) {
            val now = System.currentTimeMillis()
            if (now - lastLog < QUIET_MS) return
            lastLog = now
            log.add("Kit: ${client.name}'s webhook $what")
        }
    }

    private class Failure(val what: String, val passing: Boolean)

    private companion object {
        const val HMAC = "HmacSHA256"
        const val TIMEOUT_MS = 5_000
        const val TRIES = 3
        const val RETRY_MS = 2_000L
        const val QUIET_MS = 60_000L

        // No Android uid stands behind a network client.
        const val NETWORK_UID = -1
    }
}

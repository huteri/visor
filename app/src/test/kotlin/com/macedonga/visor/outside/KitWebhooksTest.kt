@file:OptIn(com.macedonga.visor.kit.protocol.KitInternal::class)

package com.macedonga.visor.outside

import android.content.Context
import android.content.SharedPreferences
import com.macedonga.visor.kit.protocol.Hello
import com.macedonga.visor.kit.protocol.Scope
import com.macedonga.visor.kit.protocol.Topics
import com.macedonga.visor.kit.server.Caller
import com.macedonga.visor.kit.server.Dispatcher
import com.macedonga.visor.kit.server.Gate
import com.macedonga.visor.kit.server.Routes
import com.macedonga.visor.log.EventLog
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class KitWebhooksTest {
    @Test fun disablingAndImmediatelyReenablingDropsOldQueueAndRetry() = runTest {
        exercise { clients -> clients.setOn(false); clients.setOn(true) }
    }

    @Test fun replacingClientDropsOldQueueAndRetry() = runTest {
        exercise { clients -> clients.add("receiver", scopes, "https://replacement.invalid", emptyList()) }
    }

    @Test fun revocationWhileOpeningStreamDoesNotWriteBody() = runTest {
        exercise(revokeBeforeWrite = true) { clients -> clients.setOn(false) }
    }

    @Test fun replacingClientWhileOpeningStreamDoesNotWriteOldBody() = runTest {
        exercise(revokeBeforeWrite = true) { clients ->
            clients.add("receiver", scopes, "https://replacement.invalid", emptyList())
        }
    }

    @Test fun admittedWriteCanFinishButCannotRetryAfterReplacement() = runTest {
        exercise(revokeDuringWrite = true) { clients ->
            clients.add("receiver", scopes, "https://replacement.invalid", emptyList())
        }
    }

    private suspend fun TestScope.exercise(
        revokeBeforeWrite: Boolean = false,
        revokeDuringWrite: Boolean = false,
        revoke: (NetworkClients) -> Unit,
    ) {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(context.getSharedPreferences("kit_network", Context.MODE_PRIVATE)).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)
        val clients = NetworkClients(context)
        clients.add("receiver", scopes, "https://old.invalid", listOf(topic.name))
        clients.setOn(true)

        val queued = CompletableDeferred<Unit>()
        var offered = false
        val routes = Routes().apply {
            stream(topic) { _, _ ->
                flow {
                    if (!offered) {
                        offered = true
                        emit(emptyList())
                        emit(emptyList())
                        queued.complete(Unit)
                    }
                    awaitCancellation()
                }
            }
        }
        val gate = object : Gate {
            override fun admit(uid: Int): Caller? = null
            override fun granted(caller: Caller): Set<Scope> = scopes
            override fun grants(): Map<String, Set<Scope>> = emptyMap()
            override fun setGranted(packageName: String, scopes: Set<Scope>) = false
            override fun greeted(caller: Caller, hello: Hello) = Unit
        }
        val dispatcher = Dispatcher("test", gate, routes) { }
        val attempts = mutableListOf<String>()
        val bodies = mutableListOf<ByteArrayOutputStream>()
        val firstAttempt = CompletableDeferred<Unit>()
        val webhooks = KitWebhooks(clients, mock(EventLog::class.java), backgroundScope) { url ->
            attempts += url
            val body = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray) {
                    if (revokeDuringWrite && attempts.size == 1) {
                        val retirement = FutureTask { revoke(clients) }
                        Thread(retirement).apply { isDaemon = true; start() }
                        // Replacement must finish even while the sender is inside the write.
                        retirement.get(5, TimeUnit.SECONDS)
                    }
                    super.write(bytes)
                }
            }.also(bodies::add)
            firstAttempt.complete(Unit)
            object : HttpURLConnection(URL(url)) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getOutputStream(): ByteArrayOutputStream {
                    if (revokeBeforeWrite && attempts.size == 1) revoke(clients)
                    return body
                }
                override fun getResponseCode() = 503
            }
        }
        try {
            webhooks.start(dispatcher)
            runCurrent()
            queued.await()
            firstAttempt.await()
            runCurrent()
            assertEquals(listOf("https://old.invalid"), attempts)
            if (revokeBeforeWrite) {
                assertEquals(0, bodies.single().size())
            } else {
                check(bodies.single().size() > 0)
                if (!revokeDuringWrite) revoke(clients)
            }
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(listOf("https://old.invalid"), attempts)
        } finally {
            dispatcher.stop()
        }
    }

    private companion object {
        val topic = Topics.GLASSES_PAIRS
        val scopes = setOfNotNull(topic.scope)
    }
}
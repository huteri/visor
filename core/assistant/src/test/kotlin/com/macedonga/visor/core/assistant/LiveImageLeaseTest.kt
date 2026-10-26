package com.macedonga.visor.core.assistant

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.coroutineContext

class LiveImageLeaseTest {
    @Test fun leaseRecheckedAfterModelPreparationSuspends() = runBlocking {
        var allowed = true
        val ready = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var writes = 0
        val turn = CoroutineScope(coroutineContext + SupervisorJob()).async {
            withContext(LiveImageLease { check(allowed) }) {
                ready.complete(Unit)
                resume.await()
                coroutineContext[LiveImageLease]?.validate()
                writes++
            }
        }
        ready.await()
        allowed = false
        resume.complete(Unit)
        try { turn.await(); fail("Revoked image must not dispatch") } catch (_: IllegalStateException) { }
        assertEquals(0, writes)
    }
}

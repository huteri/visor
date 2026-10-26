package com.macedonga.visor.core.assistant

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Logical diary conversation, carried without changing the answer adapter's public API. */
class ConversationContext(val diary: Diary, val conversation: String, val replay: Boolean = false) : AbstractCoroutineContextElement(Key) {
    val generation = diary.generation()
    fun validate() = diary.validateGeneration(generation)
    suspend fun <T> serialized(block: suspend () -> T): T {
        if (kotlin.coroutines.coroutineContext[Held]?.context === this) return block()
        val mutex = synchronized(locks) { locks.getOrPut(diary) { mutableMapOf() }.getOrPut(conversation) { kotlinx.coroutines.sync.Mutex() } }
        mutex.lock()
        try {
            validate()
            return kotlinx.coroutines.withContext(Held(this)) { block() }
        } finally { mutex.unlock() }
    }
    private class Held(val context: ConversationContext) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }
    companion object Key : CoroutineContext.Key<ConversationContext> {
        private val locks = java.util.WeakHashMap<Diary, MutableMap<String, kotlinx.coroutines.sync.Mutex>>()
    }
}

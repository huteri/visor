package com.macedonga.visor.core.assistant

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Revalidated at each model transport boundary, after dispatcher/toolbox suspensions. */
internal class LiveImageLease(val validate: () -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LiveImageLease>
}

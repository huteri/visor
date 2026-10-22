package com.macedonga.visor.core.assistant

/** Metadata only. Per-link aliases, at most 16 records per trace; never payloads or identifiers. */
internal class NativeDeliveryTrace(
    val alias: Int,
    private val request: String,
    private val parent: String?,
    private val note: (String) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val started = clock()
    private var records = 0
    @Synchronized fun emit(metadata: String) {
        if (records++ < 16) note("NativeDelivery: turn=$alias elapsedMs=${clock() - started} $metadata")
    }
    fun receipt(event: Int, id: String) {
        val owner = when {
            id.isEmpty() -> "absent"
            id == request -> "request"
            id == parent -> "parent"
            else -> "other"
        }
        emit("stage=received event=$event owner=$owner")
    }
}

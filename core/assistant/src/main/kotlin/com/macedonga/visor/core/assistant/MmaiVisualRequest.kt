package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.protocol.Thrift

/** Only ev65's observed outer field 1 is understood. Field 2 remains opaque, NOT ev57. */
internal object MmaiVisualRequest {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun interactionId(body: ByteArray): String? {
        if (body.size > 512 * 1024) return null
        val id = runCatching { Thrift.loads(body)[1] as? String }.getOrNull() ?: return null
        return id.takeIf { uuid.matches(it) }
    }
}

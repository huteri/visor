package com.macedonga.visor.core.session

import com.macedonga.visor.core.protocol.Header
import com.macedonga.visor.core.protocol.MessageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** What the mux needs from the link below it: plaintext in, plaintext out. */
interface PlaintextLink {

    suspend fun send(plaintext: ByteArray)

    suspend fun receive(): ByteArray
}

/** One decoded DataX message: its header and its application payload. */
class DataXMessage(
    val info: MessageInfo,
    val payload: ByteArray,
    // Close delivery removes the binding before the session routes the message.
    internal val closedServiceId: Int? = null,
)

/** The channel mux riding on one [PlaintextLink]. */
class DataXConnection(
    private val link: PlaintextLink,
    private val registry: ChannelRegistry,
) {

    // Messages waiting in front of anything the reader has produced.
    private val inbox = ArrayDeque<DataXMessage>()

    // Filled by the reader coroutine when one is running, and by nothing otherwise.
    private val fromReader = Channel<DataXMessage>(
        capacity = Channel.UNLIMITED,
        onUndeliveredElement = { message ->
            queued.decrementAndGet()
            inbox.addFirst(message)
        },
    )

    // How many messages are sitting in fromReader.
    private val queued = java.util.concurrent.atomic.AtomicInteger()

    @Volatile
    private var reader: Job? = null

    // RX messageType delta-decode cache.
    private val rxLastType = HashMap<Int, Int>()

    // A message whose payload did not fit in the frame that carried it. One slot is enough:
    // a fragment is continued immediately, never interleaved with another channel's message.
    private var fragment: Fragment? = null

    private class Fragment(val info: MessageInfo, val buffer: ByteArray, val needed: Int)

    /** True when [receive] can return a message without waiting on the peer. */
    val buffered: Boolean get() = inbox.isNotEmpty() || queued.get() > 0

    /** True once [startReader] has run. */
    val hasReader: Boolean get() = reader != null

    /** Start the one coroutine that reads the link into this connection, and return its job. */
    fun startReader(scope: CoroutineScope): Job {
        check(reader == null) { "this connection already has a reader" }
        val job = scope.launch {
            try {
                while (true) {
                    for (message in decode(link.receive())) {
                        queued.incrementAndGet()
                        fromReader.send(message)
                    }
                }
            } catch (cancellation: CancellationException) {
                fromReader.close()
                throw cancellation
            } catch (error: Throwable) {
                fromReader.close(error)
            }
        }
        reader = job
        return job
    }

    /** Allocate a local channel id for [serviceId] and bind it in the registry. */
    fun openChannel(serviceId: Int): Int {
        val channelId = registry.nextLocalChannel()
        registry.bindLocal(channelId, serviceId)
        return channelId
    }

    /** Send one DataX message on [channelId]. */
    suspend fun send(
        channelId: Int,
        payload: ByteArray,
        messageType: Int? = null,
        serviceId: Int? = null,
    ) {
        val header = Header.build(
            channelId = channelId,
            dataLen = payload.size,
            serviceId = serviceId,
            messageType = messageType,
        )
        link.send(header + payload)
    }

    // This connection's one channel per service. The glasses answer on every open channel of a
    // service, so a channel per request multiplied every reply.
    private val serviceChannels = HashMap<Int, Int>()

    /** Send on this connection's one channel for [serviceId], opening it the first time. */
    suspend fun sendOnService(serviceId: Int, messageType: Int, payload: ByteArray) {
        val known = synchronized(serviceChannels) { serviceChannels[serviceId] }
        if (known != null) {
            send(known, payload, messageType = messageType)
            return
        }
        val channel = openChannel(serviceId)
        synchronized(serviceChannels) { serviceChannels[serviceId] = channel }
        send(channel, payload, messageType = messageType, serviceId = serviceId)
    }

    /** Send a bare DataX status frame: an error id tag, zero payload, on [channelId]. */
    suspend fun sendStatus(channelId: Int, errorId: Int) {
        link.send(Header.build(channelId, 0, errorId = errorId))
    }

    /** The next DataX message, decrypting more link frames as needed. */
    suspend fun receive(): DataXMessage = deliver(nextMessage())

    private suspend fun nextMessage(): DataXMessage {
        inbox.removeFirstOrNull()?.let { return it }
        if (reader != null) return fromReader.receive().also { queued.decrementAndGet() }
        while (inbox.isEmpty()) {
            inbox.addAll(decode(link.receive()))
        }
        return inbox.removeFirst()
    }

    private fun deliver(message: DataXMessage): DataXMessage {
        if (message.info.flags.isClosed) {
            val serviceId = message.closedServiceId?.takeIf { it != 0 }
                ?: registry.serviceForInbound(message.info.channelId)
            registry.closeInbound(message.info.channelId)
            return DataXMessage(message.info, message.payload, serviceId)
        }
        return message
    }

    /** Return messages to the front of the inbox, in their original order. */
    fun pushback(messages: List<DataXMessage>) {
        for (message in messages.asReversed()) {
            inbox.addFirst(message)
        }
    }

    /** Read whatever is already decoded, without waiting on the peer at all. */
    fun poll(): DataXMessage? {
        inbox.removeFirstOrNull()?.let { return deliver(it) }
        return fromReader.tryReceive().getOrNull()
            ?.also { queued.decrementAndGet() }
            ?.let { deliver(it) }
    }

    private fun decode(plaintext: ByteArray): List<DataXMessage> =
        splitMessages(plaintext).map { DataXMessage(applyTypeCache(it.info), it.payload) }

    /** Update or apply the per-channel messageType cache. */
    private fun applyTypeCache(info: MessageInfo): MessageInfo {
        val channel = info.channelId
        if (info.messageType != 0) {
            rxLastType[channel] = info.messageType
            return info
        }
        if (!info.flags.hasError) {
            val cached = rxLastType[channel]
            if (cached != null) return info.copy(messageType = cached)
        }
        return info
    }

    /** Walk [plaintext] into DataX messages by `wireTotal = 4 + length`. */
    private fun splitMessages(plaintext: ByteArray): List<DataXMessage> {
        val messages = mutableListOf<DataXMessage>()
        var pos = 0

        val held = fragment
        if (held != null) {
            // Continuation bytes, with no header of their own.
            val body = withoutPad(plaintext)
            val take = minOf(held.needed - held.buffer.size, body.size)
            val buffer = held.buffer + body.copyOfRange(0, take)
            pos = take
            if (buffer.size < held.needed) {
                fragment = Fragment(held.info, buffer, held.needed)
                return messages
            }
            fragment = null
            messages += DataXMessage(held.info, buffer)
        }

        while (plaintext.size - pos >= 4) {
            val info = Header.parse(plaintext.copyOfRange(pos, plaintext.size))
            val wireTotal = info.wireTotal
            if (plaintext.size - pos < wireTotal) {
                // Strip the pad and look again.
                val body = withoutPad(plaintext)
                val remaining = body.size - pos
                if (remaining >= 4 && remaining < wireTotal) {
                    // `length` counts the extension tags, so what is still owed is the
                    // declared length less the tags already read. That is dataLength.
                    val needed = info.dataLength
                    // Clamped, because `headerLen` was measured on the UNSTRIPPED plaintext (the
                    // tag walker will read pad bytes as extension tags) while the head is copied
                    // out of the stripped body.
                    val from = minOf(pos + info.headerLen, body.size)
                    val head = body.copyOfRange(from, body.size)
                    fragment = Fragment(info, head, needed)
                }
                break
            }
            messages += DataXMessage(
                info,
                plaintext.copyOfRange(pos + info.headerLen, pos + wireTotal),
            )
            pos += wireTotal
        }
        return messages
    }

    companion object {
        /** The ack channel for a peer-originated channel: clear the secure bit, 0x8000. */
        fun mirror(channelId: Int): Int = channelId and 0x7FFF
    }
}

/** A frame's plaintext without its modified PKCS#7 tail. */
internal fun withoutPad(plaintext: ByteArray): ByteArray {
    if (plaintext.isEmpty()) return plaintext
    val last = plaintext[plaintext.size - 1].toInt() and 0xFF
    val padLen = (last + 0x40) and 0xFF
    if (padLen in 1..16 && plaintext.size >= padLen &&
        (plaintext.size - padLen until plaintext.size).all {
            (plaintext[it].toInt() and 0xFF) == last
        }
    ) {
        return plaintext.copyOfRange(0, plaintext.size - padLen)
    }
    return plaintext
}

package com.macedonga.visor.core.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import com.macedonga.visor.core.session.DataXConnection
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** svc10, ATC LinkManager2: moves the glasses onto a network. From lensbreak/readout/atc_wifi.py. */
const val SVC_ATC = 10
private const val MT_JOIN_ACCESS_POINT = 0x1010
const val MT_JOIN_ACCESS_POINT_RESULT = 0x1011

/** LeaveAccessPoint, empty: the glasses drop off the network they joined. */
const val MT_LEAVE_ACCESS_POINT = 0x1012

/** Link type 7, HIGH: what media is registered as, and what gets 4096-byte chunks on Wi-Fi. */
const val LINK_WIFI = 7

/** Media cannot come over Wi-Fi this time, for [message]. */
class WifiUnavailable(message: String, val kind: ImportProblem.Kind = ImportProblem.Kind.WIFI_FAILED) : Exception(message)

/**
 * Why an import did not bring [handles] over, for a person to read. It belongs to those captures: it goes once they are
 * saved, and the next import starts without it.
 */
data class ImportProblem(val kind: Kind, val detail: String = "", val handles: Set<String> = emptySet()) {
    enum class Kind {
        /** Android's Wi-Fi Direct took no call at all: only turning Wi-Fi off and on brings it back. */
        WIFI_STUCK,
        WIFI_FAILED,
        GLASSES_DID_NOT_JOIN,
        NO_WIFI_SESSION,
        WIFI_OFF,
        NOT_ALLOWED,
        FAILED,

        /** The glasses went quiet or away halfway: asleep in the closed case, or out of reach. */
        CUT_OFF,

        /** A file failed its checksum. The glasses count it delivered all the same. */
        DAMAGED,

        /** Everything came, but the phone's gallery would not take it. */
        NOT_SAVED,
    }
}

class JoinResult(val status: Int, val address: String?, val port: Int) {
    val ok: Boolean get() = status == 1 && !address.isNullOrEmpty() && port > 0
    val statusName: String get() = JOIN_STATUS.getOrElse(status) { "status $status" }
}

private val JOIN_STATUS = listOf(
    "UNKNOWN", "SUCCESS", "INTERNAL_ERROR", "JOIN_FAILED", "UNAVAILABLE", "BAD_INPUT", "TIMED_OUT",
    "CANCELLED", "BUSY", "WIFI_NOT_ENABLED", "THROTTLED_THERMAL", "THROTTLED_LOW_BATTERY",
)

/** JoinAccessPoint's bandwidth, the ATC Bandwidth enum. */
const val BW_UNSPECIFIED = 0
const val BW_20MHZ = 1
const val BW_40MHZ = 2
const val BW_80MHZ = 3

/**
 * JoinAccessPoint: put the glasses on [ssid]. A phone-owned group is an ordinary WPA2 access point
 * to them; JoinWifiDirectGroup has never worked. The uuid must be v4 or they answer status 2.
 * [bandwidth] is what the Meta app sends, but joining they take the access point's width anyway:
 * the glasses' ATC applies it only to a SoftAP of their own.
 */
suspend fun joinAccessPoint(connection: DataXConnection, ssid: String, passphrase: String, bandwidth: Int = BW_UNSPECIFIED) {
    val uuid = UUID.randomUUID()
    val raw = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
    val payload = ProtoWriter().string(1, ssid).string(2, passphrase).bytes(3, raw).varint(4, bandwidth.toLong()).build()
    connection.sendOnService(SVC_ATC, MT_JOIN_ACCESS_POINT, payload)
}

/** JoinAccessPointResult: 1 status, 2 addresses (NetworkAddress, the address as text in 3), 3 port. */
fun parseJoinResult(payload: ByteArray): JoinResult {
    val f = fields(payload)
    val address = f[2].orEmpty().filterIsInstance<ByteArray>()
        .map { fields(it).bytes(3).decodeToString() }
        .firstOrNull { it.isNotEmpty() }
    return JoinResult(f.long(1).toInt(), address, f.long(3).toInt())
}

/**
 * What Wi-Fi Direct needs at runtime: location before Android 13, Nearby devices from 13, and from
 * 17 local network access too, without which the glasses' socket silently never answers.
 */
val wifiDirectPermissions: Array<String> = when {
    Build.VERSION.SDK_INT >= 37 -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_LOCAL_NETWORK)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

fun wifiDirectAllowed(context: Context): Boolean =
    wifiDirectPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

fun wifiIsOn(context: Context): Boolean = context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true

/** A Wi-Fi Direct group this phone owns, the way the Meta app does it. The glasses only join. */
class PhoneGroup(private val context: Context, private val note: (String) -> Unit = {}) {
    /** What the glasses are told to join: ours, or Android's own when it would only make one of its own. */
    var ssid = "DIRECT-Vi-Visor"
        private set
    var passphrase: String = SecureRandom().let { random -> (1..20).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("") }
        private set

    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null

    /**
     * [band] 2 or 5 pins the group to 2.4 or 5 GHz; 0 lets the phone choose. Returns the frequency in MHz. A dropped
     * channel or a group a killed run left up can make the first try fail, so a fresh channel tries again, and last
     * Android makes a group with a name and password of its own.
     */
    @SuppressLint("MissingPermission")
    suspend fun create(band: Int = 0): Int {
        val m = manager ?: throw WifiUnavailable("this phone has no Wi-Fi Direct")
        var why = ""
        var answered = false
        for (attempt in 0 until ATTEMPTS) {
            channel?.close()
            val ch = m.initialize(context, p2pLooper) { note("Wi-Fi Direct: Android dropped the channel") }
                ?: throw WifiUnavailable("Wi-Fi Direct would not start")
            channel = ch
            val state = withTimeoutOrNull(P2P_MILLIS) { suspendCancellableCoroutine { cont -> m.requestP2pState(ch) { cont.resume(it) } } }
            note("Wi-Fi Direct try ${attempt + 1}: state ${when (state) { null -> "no answer"; WifiP2pManager.WIFI_P2P_STATE_ENABLED -> "on"; else -> "off" }}")
            if (state != null) answered = true
            if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                why = "Wi-Fi Direct is off on this phone"
                delay(RETRY_MILLIS)
                continue
            }
            // A group left up answers "removed" at once and goes a moment later; creating before it has gone fails.
            val removed = act { m.removeGroup(ch, it) }
            note("Wi-Fi Direct try ${attempt + 1}: old group ${if (removed == null) "removed" else reason(removed)}")
            if (removed == null) {
                for (i in 0 until 12) {
                    groupInfo(m, ch) ?: break
                    delay(250)
                }
            }
            val own = attempt == ATTEMPTS - 1
            val refused = if (own) act { m.createGroup(ch, it) } else act { m.createGroup(ch, config(band), it) }
            note("Wi-Fi Direct try ${attempt + 1}: ${if (own) "Android's own group" else "our group"} ${if (refused == null) "accepted" else reason(refused)}")
            if (refused == null) {
                // createGroup only accepts the request; the group is up once it reports an owner.
                repeat(40) {
                    val group = groupInfo(m, ch)
                    if (group?.isGroupOwner == true) {
                        if (own) {
                            ssid = group.networkName
                            passphrase = group.passphrase
                        }
                        return group.frequency
                    }
                    delay(250)
                }
                why = "the group never came up"
            } else {
                if (refused != NO_ANSWER) answered = true
                why = reason(refused)
            }
            delay(RETRY_MILLIS)
        }
        throw WifiUnavailable(
            "the phone would not create a Wi-Fi Direct group ($why)",
            if (answered) ImportProblem.Kind.WIFI_FAILED else ImportProblem.Kind.WIFI_STUCK,
        )
    }

    private suspend fun groupInfo(m: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pGroup? =
        withTimeoutOrNull(P2P_MILLIS) { suspendCancellableCoroutine<WifiP2pGroup?> { cont -> m.requestGroupInfo(ch) { cont.resume(it) } } }

    private fun config(band: Int) = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(passphrase).apply {
        when (band) {
            2 -> setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            5 -> setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
        }
    }.build()

    suspend fun remove() {
        val m = manager ?: return
        val ch = channel ?: return
        act { m.removeGroup(ch, it) }
        ch.close()
        channel = null
    }

    private companion object {
        const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val ATTEMPTS = 3
        const val RETRY_MILLIS = 1_000L

        fun reason(code: Int) = when (code) {
            NO_ANSWER -> "Android did not answer"
            WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
            WifiP2pManager.BUSY -> "busy"
            WifiP2pManager.ERROR -> "internal error"
            else -> "error $code"
        }
    }
}

/** Wi-Fi Direct answers on a thread of its own, so nothing on the main thread can hold them up. */
private val p2pLooper: android.os.Looper by lazy { android.os.HandlerThread("visor-p2p").apply { start() }.looper }

/**
 * A WifiP2pManager call: null on success, else its failure reason. A channel the framework has dropped never calls
 * back, and an import waiting on it forever blocked every later one: past a few seconds it counts as no answer. Success
 * comes back as its own value, so it can never be mistaken for that timeout.
 */
private suspend fun act(call: (WifiP2pManager.ActionListener) -> Unit): Int? {
    val answer = withTimeoutOrNull(P2P_MILLIS) {
        suspendCancellableCoroutine<Int> { cont ->
            call(
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = cont.resume(SUCCESS)
                    override fun onFailure(reason: Int) = cont.resume(reason)
                },
            )
        }
    } ?: return NO_ANSWER
    return answer.takeIf { it != SUCCESS }
}

private const val SUCCESS = Int.MIN_VALUE
private const val P2P_MILLIS = 8_000L
private const val NO_ANSWER = -1

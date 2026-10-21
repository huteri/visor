package com.macedonga.visor.home

import android.content.Context
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.LivePhase
import com.macedonga.visor.core.session.LinkState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The glasses' batteries as last read, for when they are out of reach. */
data class LastBattery(val glasses: Int, val case: Int?, val at: Long)

/** How the glasses were the last time they said: folded, docked in the case. Their sleep looks like silence. */
data class LastPosture(val folded: Boolean, val inCase: Boolean)

/**
 * How the glasses were when last heard from, kept across launches. One for the process, so the service writes it
 * while no screen is open and the home, the widget and the tile all read the same thing.
 */
@Singleton
class LastSeen @Inject constructor(
    @ApplicationContext context: Context,
    connection: GlassesConnection,
    live: LiveStream,
) {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _battery = MutableStateFlow(readBattery())
    private val _posture = MutableStateFlow(LastPosture(prefs.getBoolean("folded", false), prefs.getBoolean("in_case", false)))
    val battery: StateFlow<LastBattery?> = _battery.asStateFlow()
    val posture: StateFlow<LastPosture> = _posture.asStateFlow()

    init {
        scope.launch {
            connection.state.collect { state ->
                val battery = state.device?.battery ?: return@collect
                val case = state.device?.case?.levelPercent()
                val was = _battery.value
                val now = LastBattery(battery.percent.toInt(), case ?: was?.case, System.currentTimeMillis())
                if (now.glasses != was?.glasses || now.case != was?.case) writeBattery(now)
                _battery.value = now
            }
        }
        scope.launch {
            connection.state.collect { state ->
                val device = state.device ?: return@collect
                if (state.link != LinkState.READY) return@collect
                // The case's "present" only says the case is known; charging folded is what says they sit in it.
                val was = _posture.value
                val inCase = device.battery?.charging?.let { it && device.hinge != "OPEN" }
                writePosture(LastPosture(folded = device.hinge?.let { it == "CLOSED" } ?: was.folded, inCase = inCase ?: was.inCase))
            }
        }
        // The glasses stream only open and out of the case: a Live that ran says so while Bluetooth is away for it, and
        // the home it goes back to does not unfold the pair once the link is back.
        scope.launch {
            live.status.collect { status -> if (status.phase == LivePhase.RUNNING) writePosture(LastPosture(folded = false, inCase = false)) }
        }
    }

    private fun writePosture(now: LastPosture) {
        if (now == _posture.value) return
        _posture.value = now
        prefs.edit().putBoolean("folded", now.folded).putBoolean("in_case", now.inCase).apply()
    }

    private fun readBattery(): LastBattery? = prefs.getInt("glasses", -1).takeIf { it >= 0 }?.let {
        LastBattery(it, prefs.getInt("case", -1).takeIf { c -> c >= 0 }, prefs.getLong("at", 0))
    }

    private fun writeBattery(b: LastBattery) =
        prefs.edit().putInt("glasses", b.glasses).putInt("case", b.case ?: -1).putLong("at", b.at).apply()
}

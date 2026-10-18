package com.macedonga.visor.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.ui.Line
import com.macedonga.visor.ui.PlainScreen
import com.macedonga.visor.ui.Reading
import com.macedonga.visor.ui.Section
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import org.json.JSONObject

@HiltViewModel
class FlagsViewModel @Inject constructor(
    val config: GlassesConfig,
    val connection: GlassesConnection,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    /** What a param does, read from the firmware's code (build 70523680060800100); only checked ones are here. */
    val notes: Map<String, FlagNote> by lazy {
        val json = JSONObject(context.assets.open("mobileconfig_notes.json").bufferedReader().use { it.readText() })
        json.keys().asSequence().associateWith { k ->
            json.getJSONObject(k).let { FlagNote(it.getString("product"), it.getString("does"), it.optString("change").ifEmpty { null }) }
        }
    }
}

class FlagNote(val product: String, val does: String, val change: String?)

/** Meta's server-side switches (MobileConfig) on the glasses, one by one. Whole features live in Hidden features. */
@Composable
fun FlagsScreen(model: FlagsViewModel = hiltViewModel()) {
    val state by model.config.state.collectAsStateWithLifecycle()
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val ready = glasses.link == LinkState.READY
    val idle = ready && state.busy.isEmpty()
    var pending by remember { mutableStateOf(mapOf<String, Any>()) }
    var query by rememberSaveable { mutableStateOf("") }
    // Whatever the glasses already hold is not a change.
    val changes = pending.filter { (k, v) -> state.values[k] != v }
    fun value(name: String) = changes[name] ?: state.values[name]

    PlainScreen("Feature flags") {
        Line(
            "Meta's switches for features it turns on from its servers. Visor reads them from a bug report and writes them back " +
                "the way the Meta app does. The glasses read most of them when they start: restart them after sending.",
            secondary = true,
        )
        Reading("Read", state.readAt?.let { AT.format(Instant.ofEpochMilli(it)) } ?: "never")
        if (state.busy.isNotEmpty()) Line(state.busy)
        if (state.note.isNotEmpty()) Line(state.note, secondary = true)
        if (!ready) Line("Connect to the glasses to read or change these.", secondary = true)
        OutlinedButton(enabled = idle, onClick = { model.config.read() }) { Text(if (state.params.isEmpty()) "Read from the glasses" else "Read again") }
        if (state.params.isEmpty()) return@PlainScreen

        Section("Checked in the firmware")
        Line(
            "On/off switches whose effect was read in the glasses' firmware code. The others may work too: nobody has traced them yet.",
            secondary = true,
        )
        val checked = model.notes.filterKeys { state.params[it]?.type == 1 }
        for ((product, names) in checked.keys.groupBy { model.notes.getValue(it).product }.toSortedMap(compareBy { PRODUCT_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } })) {
            Line(product)
            for (n in names.sorted()) {
                val note = model.notes.getValue(n)
                FlagRow(n, listOfNotNull(note.does, note.change).joinToString(" "), checked = value(n) == true, enabled = true) { on ->
                    pending = pending + (n to on)
                }
            }
        }

        Section("Every flag")
        OutlinedTextField(query, { query = it }, placeholder = { Text("Search by name, e.g. live_ai") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val needle = query.trim().lowercase().replace(' ', '_')
        if (needle.length >= 2) {
            val found = state.params.values.filter { it.type == 1 && needle in it.name }.sortedBy { it.name }
            if (found.isEmpty()) Line("Nothing matches.", secondary = true)
            for (p in found.take(SHOWN)) {
                FlagRow(p.name.substringAfter('.'), model.notes[p.name]?.does ?: p.name.substringBefore('.'), checked = value(p.name) == true, enabled = true) { on ->
                    pending = pending + (p.name to on)
                }
            }
            if (found.size > SHOWN) Line("${found.size - SHOWN} more; type more of the name.", secondary = true)
        }

        if (changes.isNotEmpty()) {
            Section("To send")
            for ((k, v) in changes) Line("$k → $v", secondary = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = idle, onClick = { model.config.apply(changes) }) { Text("Send to the glasses") }
                OutlinedButton(onClick = { pending = emptyMap() }) { Text("Discard") }
            }
        }

        state.baseline?.let {
            Section("Way back")
            Line("Visor kept the values the glasses had before its first change.", secondary = true)
            OutlinedButton(enabled = idle, onClick = { pending = emptyMap(); model.config.restore() }) { Text("Restore the original values") }
        }
    }
}

@Composable
private fun FlagRow(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Visor.type.body, color = Visor.colors.textPrimary)
            Text(detail, style = Visor.type.label, color = Visor.colors.textSecondary)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

private const val SHOWN = 60

// Which glasses a param is for, as its namespace says; the firmware is one image for all of them.
private val PRODUCT_ORDER = listOf("glasses", "Ray-Ban Meta", "Oakley", "Ray-Ban Display", "neural band", "Meta AI app", "iPhone app")

private val AT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault())

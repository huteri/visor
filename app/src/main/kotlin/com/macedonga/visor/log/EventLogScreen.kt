package com.macedonga.visor.log

import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.Footnote
import com.macedonga.visor.settings.Hairline
import com.macedonga.visor.settings.MONO
import com.macedonga.visor.gallery.RoundIcon
import com.macedonga.visor.settings.SettingsPage
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject

@HiltViewModel
class EventLogViewModel @Inject constructor(
    val log: EventLog,
    val media: MediaImport,
    val assistant: com.macedonga.visor.core.assistant.Assistant,
) : ViewModel()

/** The log as one card of lines, opened at the end: what matters is what just happened. */
@Composable
fun EventLogScreen(model: EventLogViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lines by model.log.lines.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (lines.isNotEmpty()) list.scrollToItem(lines.size + 1) }

    SettingsPage(
        stringResource(R.string.settings_event_log),
        stringResource(R.string.event_log_lede),
        light = 0.3f,
        list = list,
        actions = {
            RoundIcon(VisorIcons.Share, stringResource(R.string.event_log_share), { share(context, model.log.text()) })
            RoundIcon(VisorIcons.Trash, stringResource(R.string.event_log_clear), model.log::clear)
        },
    ) {
        if (lines.isEmpty()) item(key = "empty") { Footnote(stringResource(R.string.event_log_empty)) }
        itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
            val shape = when {
                lines.size == 1 -> RoundedCornerShape(18.dp)
                i == 0 -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
                i == lines.lastIndex -> RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)
                else -> RectangleShape
            }
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(Visor.colors.raised)) {
                if (i > 0) Hairline()
                LogRow(line)
            }
        }
        item(key = "visual-capture") {
            Box(Modifier.padding(horizontal = 32.dp, vertical = 20.dp)) {
                VisualCaptureControls(model.assistant)
            }
        }
        item(key = "bug-report") {
            Box(Modifier.padding(horizontal = 32.dp, vertical = 20.dp)) {
                ActionPill(stringResource(R.string.event_log_bug_report), model.media::requestBugReport, quiet = true)
            }
        }
    }
}

/** `HH:mm:ss.SSS  what happened`: the time in the margin, a failure in the signal colour. */
@Composable
private fun LogRow(line: String) {
    val colors = Visor.colors
    val time = line.substringBefore("  ", "")
    val text = line.substringAfter("  ", line)
    val failed = text.startsWith("Warning") || text.contains("failed", ignoreCase = true) || text.contains("crashed")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(time.dropLast(4), style = MONO, color = colors.textTertiary, modifier = Modifier.width(56.dp).padding(top = 3.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = Visor.type.label, color = if (failed) colors.signal else colors.textPrimary)
    }
}

/** The log as a text file for mail or chat, headed by what it came from. */
private fun share(context: Context, text: String) {
    val webView = WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"
    val head = "Visor ${BuildConfig.VERSION_NAME}, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, WebView $webView"
    val file = File(context.cacheDir, "log/visor-log.txt").apply {
        parentFile?.mkdirs()
        writeText(head + "\n\n" + text + "\n")
    }
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Visor event log")
        .putExtra(Intent.EXTRA_TEXT, head)
        .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, context.packageName + ".files", file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}

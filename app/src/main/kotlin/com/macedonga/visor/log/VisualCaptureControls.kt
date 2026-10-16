package com.macedonga.visor.log

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.Footnote

/** Separate from the ordinary event-log export: these bytes may contain private request data. */
@Composable
internal fun VisualCaptureControls(assistant: Assistant) {
    val context = LocalContext.current
    var action by remember { mutableStateOf(0) }
    Column {
        Footnote(stringResource(R.string.visual_capture_help))
        ActionPill(stringResource(R.string.visual_capture_start), { action = 1 }, quiet = true)
        ActionPill(stringResource(R.string.visual_capture_share), { action = 2 }, quiet = true)
        ActionPill(stringResource(R.string.visual_capture_delete), {
            assistant.clearVisualCapture()
            Toast.makeText(context, R.string.visual_capture_deleted, Toast.LENGTH_SHORT).show()
        }, quiet = true)
    }
    if (action != 0) AlertDialog(
        onDismissRequest = { action = 0 },
        title = { Text(stringResource(if (action == 1) R.string.visual_capture_start else R.string.visual_capture_share)) },
        text = { Text(stringResource(R.string.visual_capture_warning)) },
        dismissButton = { TextButton(onClick = { action = 0 }) { Text(stringResource(R.string.editor_cancel)) } },
        confirmButton = {
            TextButton(onClick = {
                if (action == 1) {
                    assistant.startVisualCapture()
                    Toast.makeText(context, R.string.visual_capture_started, Toast.LENGTH_LONG).show()
                } else {
                    shareVisualCapture(context, assistant)
                }
                action = 0
            }) { Text(stringResource(if (action == 1) R.string.visual_capture_start else R.string.visual_capture_share)) }
        },
    )
}

private fun shareVisualCapture(context: Context, assistant: Assistant) {
    val uri = assistant.exportVisualCapture()
    if (uri == null) {
        Toast.makeText(context, R.string.visual_capture_empty, Toast.LENGTH_LONG).show()
        return
    }
    try {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.visual_capture_share))
            .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.visual_capture_warning))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = android.content.ClipData.newRawUri(context.getString(R.string.visual_capture_share), uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: Exception) {
        Toast.makeText(context, R.string.visual_capture_export_failed, Toast.LENGTH_LONG).show()
    }
}

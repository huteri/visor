package com.macedonga.visor.gallery

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.macedonga.visor.R
import com.macedonga.visor.core.media.ImportProblem
import com.macedonga.visor.core.media.wifiDirectAllowed
import com.macedonga.visor.core.media.wifiDirectPermissions
import com.macedonga.visor.core.media.wifiIsOn
import com.macedonga.visor.settings.openAppPage

/**
 * Every import first asks Android for everything it can need: what is already granted comes back at once with no
 * dialog, what is missing is asked for there and then. Turned down for good, Visor's own page opens instead. With Wi-Fi
 * off, its panel opens; otherwise the import goes on and says itself why, if it still falls short.
 */
@Composable
fun rememberImportGate(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    var waiting by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        val go = waiting
        waiting = null
        val refusedForGood = answers.any { (name, granted) -> !granted && name in wifiDirectPermissions && activity?.shouldShowRequestPermissionRationale(name) == false }
        when {
            !wifiDirectAllowed(context) && refusedForGood -> openAppPage(context)
            !wifiIsOn(context) -> openWifiPanel(context)
            else -> go?.invoke()
        }
    }
    return { go ->
        waiting = go
        ask.launch(importPermissions())
    }
}

/** Wi-Fi Direct's own, the local network the session runs on, and Bluetooth, which asks the glasses to join. */
private fun importPermissions(): Array<String> = buildSet {
    addAll(wifiDirectPermissions)
    if (Build.VERSION.SDK_INT >= 36) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
    }
}.toTypedArray()

private fun openWifiPanel(context: android.content.Context) {
    runCatching { context.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Why the last import fell short, in the phone's language. */
@Composable
fun importProblemText(problem: ImportProblem): String = when (problem.kind) {
    ImportProblem.Kind.WIFI_STUCK -> stringResource(R.string.import_problem_stuck)
    ImportProblem.Kind.WIFI_FAILED -> stringResource(R.string.import_problem_wifi, problem.detail)
    ImportProblem.Kind.GLASSES_DID_NOT_JOIN -> stringResource(R.string.import_problem_join)
    ImportProblem.Kind.NO_WIFI_SESSION -> stringResource(R.string.import_problem_session)
    ImportProblem.Kind.WIFI_OFF -> stringResource(R.string.import_problem_wifi_off)
    ImportProblem.Kind.NOT_ALLOWED -> stringResource(R.string.import_problem_not_allowed)
    ImportProblem.Kind.FAILED -> stringResource(R.string.import_problem_failed, problem.detail)
    ImportProblem.Kind.CUT_OFF -> stringResource(R.string.import_problem_cut_off)
    ImportProblem.Kind.DAMAGED -> stringResource(R.string.import_problem_damaged)
    ImportProblem.Kind.NOT_SAVED -> stringResource(R.string.import_problem_not_saved, problem.detail)
}

package com.boko.roudio.ui.setup

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.boko.roudio.system.PhoneSettings
import com.boko.roudio.system.PhoneSettings.Autostart

/**
 * 11c with Android behind it. Rows re-check when the screen comes back (the owner may have
 * changed something in Android settings). Nearby devices refused for good goes to 12c; any other
 * permission Android won't ask for again opens Roudio's settings page.
 */
@Composable
fun PermissionsRoute(onContinue: () -> Unit, onNearbyRefused: () -> Unit, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    fun read() = PermissionRows(
        nearbyDevices = PhoneSettings.granted(context, PhoneSettings.nearbyDevices),
        notifications = PhoneSettings.granted(context, PhoneSettings.notifications),
        nearbyWifi = PhoneSettings.granted(context, PhoneSettings.nearbyWifi),
    )
    var rows by remember { mutableStateOf(read()) }
    LifecycleResumeEffect(Unit) {
        rows = read()
        onPauseOrDispose {}
    }

    var continueAfterNearby by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        rows = read()
        val refusedForGood = result.filter { (permission, granted) ->
            !granted && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }.keys
        when {
            refusedForGood.any { it in PhoneSettings.nearbyDevices } -> onNearbyRefused()
            refusedForGood.isNotEmpty() -> context.startActivity(PhoneSettings.appDetails(context))
        }
        if (continueAfterNearby && rows.nearbyDevices) onContinue()
        continueAfterNearby = false
    }

    PermissionsScreen(
        allowed = rows,
        onAllowNearbyDevices = { request.launch(PhoneSettings.nearbyDevices) },
        onAllowNotifications = { request.launch(PhoneSettings.notifications) },
        onAllowNearbyWifi = { request.launch(PhoneSettings.nearbyWifi) },
        onContinue = {
            if (rows.nearbyDevices) {
                onContinue()
            } else {
                continueAfterNearby = true
                request.launch(PhoneSettings.nearbyDevices)
            }
        },
        onBack = onBack,
    )
}

/** 11d with Android behind it. */
@Composable
fun KeepRunningRoute(onContinue: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    fun read() = KeepRunningState(
        autostart = if (PhoneSettings.isXiaomi) PhoneSettings.autostart(context) else null,
        autostartConfirmed = prefs.getBoolean(KEY_AUTOSTART_CONFIRMED, false),
        batteryUnrestricted = PhoneSettings.batteryUnrestricted(context),
    )
    var state by remember { mutableStateOf(read()) }
    var openedAutostart by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        // Android can't tell us Autostart here, so coming back from its screen counts as done.
        if (openedAutostart && PhoneSettings.autostart(context) == Autostart.Unknown) {
            prefs.edit { putBoolean(KEY_AUTOSTART_CONFIRMED, true) }
        }
        openedAutostart = false
        state = read()
        onPauseOrDispose {}
    }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        state = read()
    }

    KeepRunningScreen(
        state = state,
        onOpenAutostart = {
            openedAutostart = true
            context.startActivity(PhoneSettings.autostartSettings(context))
        },
        onOpenBattery = {
            val ask = PhoneSettings.requestBatteryUnrestricted(context)
            val intent = if (ask.resolveActivity(context.packageManager) != null) ask else PhoneSettings.appDetails(context)
            battery.launch(intent)
        },
        onContinue = onContinue,
        onBack = onBack,
    )
}

private const val PREFS = "setup"
private const val KEY_AUTOSTART_CONFIRMED = "autostart_confirmed"

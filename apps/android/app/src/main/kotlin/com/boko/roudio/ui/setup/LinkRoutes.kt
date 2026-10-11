package com.boko.roudio.ui.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.boko.roudio.R
import com.boko.roudio.recorder.FoundRecorder
import com.boko.roudio.recorder.LinkFailure
import com.boko.roudio.recorder.LinkPhase
import com.boko.roudio.recorder.Recorder
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.system.PhoneSettings
import com.boko.roudio.ui.recorder.batteryText
import com.boko.roudio.ui.recorder.freeSpaceText
import com.boko.roudio.ui.recorder.recordingText
import kotlinx.coroutines.delay

/**
 * 11f, 11g and the errors that can replace them (12a, 12b, 12c), driven by [Recorder]. Looks as
 * soon as it can; leaving clears the search so nothing keeps running.
 */
@Composable
fun FindRecorderRoute(recorder: Recorder, onLinking: () -> Unit, onUnlinkHelp: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by recorder.state.collectAsState()
    fun nearbyAllowed() = PhoneSettings.granted(context, PhoneSettings.nearbyDevices)
    var allowed by remember { mutableStateOf(nearbyAllowed()) }
    var refusedForGood by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        allowed = nearbyAllowed()
        refusedForGood = !allowed && activity != null &&
            result.keys.any { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    }
    LifecycleResumeEffect(Unit) {
        allowed = nearbyAllowed()
        onPauseOrDispose {}
    }
    LaunchedEffect(allowed) { if (!allowed && !refusedForGood) request.launch(PhoneSettings.nearbyDevices) }

    val idle = state.link == LinkPhase.Idle
    LaunchedEffect(allowed, state.bluetoothOn, idle) {
        if (allowed && state.bluetoothOn && idle) recorder.search()
    }

    var lastFound by remember { mutableStateOf(emptyList<FoundRecorder>()) }
    val searching = state.link is LinkPhase.Searching
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(searching) {
        seconds = 0
        while (searching) {
            delay(1_000)
            seconds++
        }
    }

    val leave = {
        recorder.clearLink()
        onBack()
    }
    BackHandler(onBack = leave)
    val turnOnBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    when {
        !allowed && refusedForGood -> PermissionRefusedRoute(onGranted = { allowed = true }, onBack = leave)
        !state.bluetoothOn -> BluetoothOffScreen(
            onTurnOn = { turnOnBluetooth.launch(PhoneSettings.enableBluetooth(context)) },
            onNotNow = leave,
        )
        state.link == LinkPhase.NothingFound -> NotFoundScreen(
            onLookAgain = recorder::search,
            onMaybePlaud = {
                recorder.clearLink()
                onUnlinkHelp()
            },
            onBack = leave,
        )
        else -> {
            // Once a recorder is picked the phase moves on, but this screen is still fading out:
            // keep showing the list that was tapped.
            val found = (state.link as? LinkPhase.Searching)?.found ?: lastFound
            lastFound = found
            if (found.isEmpty()) {
                FindingScreen(seconds, onStop = leave, onBack = leave)
            } else {
                FoundScreen(
                    found = found,
                    onConnect = {
                        recorder.link(it)
                        onLinking()
                    },
                    onLinkedElsewhere = {
                        recorder.showLinkedElsewhere(it)
                        onLinking()
                    },
                    onKeepLooking = recorder::search,
                    onBack = leave,
                )
            }
        }
    }
}

/**
 * 11h, then 12d or 12e if it fails. Once the handshake is done, waits (up to 10 s) for the first
 * battery and storage readings so 11i has something to show.
 */
@Composable
fun LinkingRoute(recorder: Recorder, onLinked: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by recorder.state.collectAsState()
    // Leaving changes the state this screen reads; make sure we only leave once.
    var gone by remember { mutableStateOf(false) }
    fun go(action: () -> Unit) {
        if (!gone) {
            gone = true
            action()
        }
    }
    val leave = {
        go {
            recorder.clearLink()
            onBack()
        }
    }
    val cancel = {
        go {
            recorder.cancelLink()
            onBack()
        }
    }
    val phase = state.link
    val tried = (phase as? LinkPhase.Linking)?.recorder ?: (phase as? LinkPhase.Failed)?.recorder
    val model = (tried?.model ?: state.linked?.model)?.shortName ?: stringResource(R.string.app_name)
    BackHandler(onBack = if (phase is LinkPhase.Linking) cancel else leave)
    val turnOnBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    if (!state.bluetoothOn) {
        BluetoothOffScreen(onTurnOn = { turnOnBluetooth.launch(PhoneSettings.enableBluetooth(context)) }, onNotNow = leave)
        return
    }
    when (phase) {
        is LinkPhase.Linking -> LinkingScreen(
            model = model,
            bluetooth = if (phase.bluetoothUp) LinkStep.Done else LinkStep.Running,
            roudio = if (phase.bluetoothUp) LinkStep.Running else LinkStep.Waiting,
            readings = LinkStep.Waiting,
            onCancel = cancel,
            onBack = cancel,
        )
        is LinkPhase.Failed -> {
            val tryAgain = { phase.recorder?.let(recorder::link) ?: leave() }
            if (phase.reason == LinkFailure.LinkedElsewhere) {
                LinkedElsewhereScreen(
                    model = model,
                    onTryAgain = tryAgain,
                    onOpenPlaud = { context.startActivity(PhoneSettings.plaudApp(context)) },
                    onBack = leave,
                )
            } else {
                LinkFailedScreen(phase.reason, onTryAgain = tryAgain, onBack = leave)
            }
        }
        else -> if (state.linked != null && state.isConnected) {
            val fresh = state.hasFreshReadings()
            LaunchedEffect(fresh) {
                if (!fresh) delay(READINGS_WAIT_MS)
                go(onLinked)
            }
            LinkingScreen(
                model = model,
                bluetooth = LinkStep.Done,
                roudio = LinkStep.Done,
                readings = if (fresh) LinkStep.Done else LinkStep.Running,
                onCancel = { go(onLinked) },
                onBack = { go(onLinked) },
            )
        } else {
            // Nothing to show: linking was cancelled or cleared elsewhere.
            LaunchedEffect(Unit) { leave() }
        }
    }
}

/** Battery and storage both reported since this connection began. */
private fun RecorderState.hasFreshReadings(): Boolean {
    val since = connectedSince ?: return false
    return battery?.at?.isBefore(since) == false && storage?.at?.isBefore(since) == false
}

private const val READINGS_WAIT_MS = 10_000L

/** 11i with live readings. */
@Composable
fun LinkedRoute(recorder: Recorder, onDone: () -> Unit) {
    val state by recorder.state.collectAsState()
    BackHandler(onBack = onDone)
    val notRead = stringResource(R.string.reading_not_read)
    LinkedScreen(
        model = state.linked?.model?.shortName ?: stringResource(R.string.app_name),
        readings = listOf(
            Reading(stringResource(R.string.reading_battery), state.battery?.value?.let { batteryText(it) } ?: notRead),
            Reading(stringResource(R.string.reading_free_space), state.storage?.value?.let { freeSpaceText(it) } ?: notRead),
            Reading(stringResource(R.string.reading_recording_now), recordingText(state.recordingNow)),
        ),
        onDone = onDone,
    )
}

/**
 * 12c: opens Roudio's settings page and comes back by itself once Nearby devices is allowed.
 * "Why Roudio needs this" shows the reason from 11c.
 */
@Composable
fun PermissionRefusedRoute(onGranted: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var why by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        if (PhoneSettings.granted(context, PhoneSettings.nearbyDevices)) onGranted()
        onPauseOrDispose {}
    }
    BackHandler(onBack = onBack)
    PermissionRefusedScreen(
        onOpenSettings = { context.startActivity(PhoneSettings.appDetails(context)) },
        onWhy = { why = true },
        onBack = onBack,
    )
    if (why) {
        AlertDialog(
            onDismissRequest = { why = false },
            title = { Text(stringResource(R.string.permission_nearby_title)) },
            text = { Text(stringResource(R.string.permission_nearby_body)) },
            confirmButton = { TextButton(onClick = { why = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}
